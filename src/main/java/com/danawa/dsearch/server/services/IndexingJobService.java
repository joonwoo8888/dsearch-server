package com.danawa.dsearch.server.services;

import com.danawa.dsearch.server.config.IndexerConfig;
import com.danawa.dsearch.server.config.ElasticsearchFactory;
import com.danawa.dsearch.server.entity.Collection;
import com.danawa.dsearch.server.entity.IndexStep;
import com.danawa.dsearch.server.entity.IndexingStatus;
import com.danawa.dsearch.server.excpetions.IndexingJobFailureException;
import com.danawa.fastcatx.indexer.IndexJobManager;
import com.danawa.fastcatx.indexer.entity.Job;
import com.google.gson.Gson;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeResponse;
import org.elasticsearch.action.admin.indices.shrink.ResizeType;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.*;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetIndexResponse;
import org.elasticsearch.client.tasks.CancelTasksRequest;
import org.elasticsearch.client.tasks.CancelTasksResponse;
import org.elasticsearch.client.tasks.TaskId;
import org.elasticsearch.client.tasks.TaskSubmissionResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.ReindexRequest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static java.lang.Thread.sleep;

@Service
@ConfigurationProperties(prefix = "dsearch.collection")
public class IndexingJobService {
    private static final Logger logger = LoggerFactory.getLogger(IndexingJobService.class);
    private final ElasticsearchFactory elasticsearchFactory;
    private final RestTemplate restTemplate;

    private final String indexHistory = ".dsearch_index_history";

    private final String jdbcSystemIndex;
    private final com.danawa.fastcatx.indexer.IndexJobManager indexerJobManager;

    private Map<String, Object> params;
    private Map<String, Object> indexing;
    private Map<String, Object> propagate;

    public IndexingJobService(ElasticsearchFactory elasticsearchFactory,
                              @Value("${dsearch.jdbc.setting}") String jdbcSystemIndex,
                              IndexJobManager indexerJobManager) {
        this.elasticsearchFactory = elasticsearchFactory;
        this.jdbcSystemIndex = jdbcSystemIndex;
        this.indexerJobManager = indexerJobManager;

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(10 * 1000);
        factory.setReadTimeout(10 * 1000);
        restTemplate = new RestTemplate(factory);
    }

    public com.danawa.fastcatx.indexer.IndexJobManager getIndexerJobManager() {
        return this.indexerJobManager;
    }

    private void addIndexHistoryException(UUID clusterId, Collection collection, String errorMessage){
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            NoticeHandler.send(String.format("%s 컬렉션의 색인이 실패하였습니다.\n%s", collection.getBaseId(), errorMessage));
            Collection.Index index = getTargetIndex(client, collection.getBaseId(), collection.getIndexA(), collection.getIndexB());

            long currentTime = System.currentTimeMillis();

            BoolQueryBuilder countQuery = QueryBuilders.boolQuery();
            countQuery.filter().add(QueryBuilders.termQuery("index", index));
            countQuery.filter().add(QueryBuilders.termQuery("startTime",  currentTime));
            countQuery.filter().add(QueryBuilders.termQuery("jobType", IndexStep.FULL_INDEX));

            CountResponse countResponse = client.count(new CountRequest(indexHistory).query(countQuery), RequestOptions.DEFAULT);
            logger.debug("index: {}, startTime: {}, jobType: {}, result Count: {}", index, currentTime, IndexStep.FULL_INDEX, countResponse.getCount());

            if (countResponse.getCount() == 0) {
                Map<String, Object> source = new HashMap<>();
                source.put("index", index);
                source.put("jobType", IndexStep.FULL_INDEX);
                source.put("startTime",  currentTime);
                source.put("endTime",  currentTime);
                source.put("autoRun", collection.isAutoRun());
                source.put("status", "ERROR");
                source.put("docSize", "0");
                source.put("store", "0");
                client.index(new IndexRequest().index(indexHistory).source(source), RequestOptions.DEFAULT);
            }
//            deleteLastIndexStatus(client, index, startTime);
        } catch (IOException e) {
            logger.error("{}", e);
        }
    }

    /**
     * 소스를 읽어들여 ES 색인에 입력하는 작업.
     * indexer를 외부 프로세스로 실행한다.
     *
     * @return IndexingStatus*/
    public IndexingStatus indexing(UUID clusterId, Collection collection, boolean autoRun, IndexStep step) throws IndexingJobFailureException {
        return indexing(clusterId, collection, autoRun, step, new ArrayDeque<>());
    }
    public IndexingStatus indexing(UUID clusterId, Collection collection, boolean autoRun, IndexStep step, Queue<IndexStep> nextStep) throws IndexingJobFailureException {
        IndexingStatus indexingStatus = new IndexingStatus();
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            Collection.Index indexA = collection.getIndexA();
            Collection.Index indexB = collection.getIndexB();

//            1. 대상 인덱스 찾기.
            logger.info("clusterId: {}, baseId: {}, 대상 인덱스 찾기", clusterId, collection.getBaseId());
            Collection.Index index = getTargetIndex(client, collection.getBaseId(), indexA, indexB);

//            2. 인덱스 설정 변경.
            logger.info("{} 인덱스 설정 변경", index);
            editPreparations(client, collection, index);

//            3. 런처 파라미터 변환작업
            logger.info("{} 런처 파라미터 변환 작업", index);
            Collection.Launcher launcher = collection.getLauncher();

            Map<String, Object> body = convertRequestParams(launcher.getYaml());
            if (collection.getJdbcId() != null && !"".equals(collection.getJdbcId())) {
                GetResponse getResponse = client.get(new GetRequest().index(jdbcSystemIndex).id(collection.getJdbcId()), RequestOptions.DEFAULT);
                Map<String, Object> jdbcSource = getResponse.getSourceAsMap();
                jdbcSource.put("driverClassName", jdbcSource.get("driver"));
                jdbcSource.put("url", jdbcSource.get("url"));
                jdbcSource.put("user", jdbcSource.get("user"));
                jdbcSource.put("password", jdbcSource.get("password"));
                body.put("_jdbc", jdbcSource);
            }

            body.put("index", index.getIndex());
            body.put("_indexingSettings", indexing);

            // null 대비 에러처리
            if(collection.getEsHost() != null && !collection.getEsHost().equals("")){
                body.put("host", collection.getEsHost());    
            }

            int esPort = 9200;
            if(collection.getEsPort() != null && !collection.getEsPort().equals("")){
                try{
                    esPort = Integer.parseInt(collection.getEsPort());
                }catch (NumberFormatException e){
                    logger.info("{}", e);
                }
                body.put("port", esPort);
            }
            
            body.put("scheme", collection.getEsScheme());
            body.put("esUsername", collection.getEsUser());
            body.put("esPassword", collection.getEsPassword());

            if (launcher.getScheme() == null || "".equals(launcher.getScheme())) {
                launcher.setScheme("http");
            }

            String indexingJobId;
//            4. indexer 색인 전송
            logger.debug("외부 인덱서 사용 여부 : {}", collection.isExtIndexer());
            if (collection.isExtIndexer()) {
                // 외부 인덱서를 사용할 경우 전송.
                ResponseEntity<Map> responseEntity = restTemplate.exchange(
                        new URI(String.format("%s://%s:%d/async/start", launcher.getScheme(), launcher.getHost(), launcher.getPort())),
                        HttpMethod.POST,
                        new HttpEntity(body),
                        Map.class
                );
                if (responseEntity.getBody() == null) {
                    logger.warn("{}", responseEntity);
                    throw new NullPointerException("Indexer Start Failed!");
                }
                indexingJobId = (String) responseEntity.getBody().get("id");
                indexingStatus.setScheme(launcher.getScheme());
                indexingStatus.setHost(launcher.getHost());
                indexingStatus.setPort(launcher.getPort());
            } else {
                // 서버 쓰래드 기반으로 색인 실행.
                Job job = indexerJobManager.start(IndexerConfig.ACTION.FULL_INDEX.name(), body);
                indexingJobId = job.getId().toString();
            }

            logger.info("Job ID: {}", indexingJobId);
            indexingStatus.setClusterId(clusterId);
            indexingStatus.setIndex(index.getIndex());
            indexingStatus.setStartTime(System.currentTimeMillis());
            indexingStatus.setIndexingJobId(indexingJobId);
            indexingStatus.setAutoRun(autoRun);
            indexingStatus.setCurrentStep(step);
            indexingStatus.setNextStep(nextStep);
            indexingStatus.setRetry(50);
            indexingStatus.setCollection(collection);
        } catch (Exception e) {
            logger.error("", e);
            // Connection Timeout 히스토리 남기기
            addIndexHistoryException(clusterId, collection, e.getMessage());
            throw new IndexingJobFailureException(e);
        }
        return indexingStatus;
    }

    public void changeRefreshInterval(UUID clusterId, Collection collection, String target) throws IOException {
        try(RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)){
            Map<String, Object> settings = propagate;

            // refresh_interval : -1, 1s, 2s, ...
            if(collection.getRefresh_interval() != null){
                // 0일때는 1로 셋팅.
                int refresh_interval = collection.getRefresh_interval() == 0 ? 1 : collection.getRefresh_interval();

                if(refresh_interval >= 0){
                    settings.replace("refresh_interval", collection.getRefresh_interval() + "s");
                } else {
                    // -2 이하로 내려갈 때, -1로 고정.
                    refresh_interval = -1;
                    settings.replace("refresh_interval", refresh_interval + "");
                }
            }

            client.indices().putSettings(new UpdateSettingsRequest().indices(target).settings(settings), RequestOptions.DEFAULT);
        }
    }

    /**
     * 색인을 대표이름으로 alias 하고 노출한다.
     * */
    public void expose(UUID clusterId, Collection collection) throws IOException {
        expose(clusterId, collection, null);
    }
    public void expose(UUID clusterId, Collection collection, String target) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            String baseId = collection.getBaseId();
            Collection.Index indexA = collection.getIndexA();
            Collection.Index indexB = collection.getIndexB();

            Collection.Index addIndex;
            Collection.Index removeIndex;
            IndicesAliasesRequest request = new IndicesAliasesRequest();

            if (target != null) {
                if (indexA.getIndex().equals(target)) {
                    addIndex = indexA;
                    removeIndex = indexB;
                } else {
                    addIndex = indexB;
                    removeIndex = indexA;
                }
                try {
                    request.addAliasAction(new IndicesAliasesRequest.
                            AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(addIndex.getIndex()).alias(baseId));
                    request.addAliasAction(new IndicesAliasesRequest.
                            AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                            .index(removeIndex.getIndex()).alias(baseId));
                    // 교체
                    client.indices().updateAliases(request, RequestOptions.DEFAULT);
                } catch (Exception e) {
                    request = new IndicesAliasesRequest();
                    request.addAliasAction(new IndicesAliasesRequest.
                            AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(addIndex.getIndex()).alias(baseId));
                    // 교체
                    client.indices().updateAliases(request, RequestOptions.DEFAULT);
                }
            } else {
                if (indexA.getUuid() == null && indexB.getUuid() == null) {
                    logger.debug("empty index");
                    return;
                } else if (indexA.getUuid() == null && indexB.getUuid() != null) {
                    // 인덱스가 하나일 경우 고정
                    request.addAliasAction(new IndicesAliasesRequest.
                            AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(indexB.getIndex()).alias(baseId));
                    target = indexB.getIndex();

                } else if (indexA.getUuid() != null && indexB.getUuid() == null) {
                    // 인덱스가 하나일 경우 고정
                    request.addAliasAction(new IndicesAliasesRequest.
                            AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                            .index(indexA.getIndex()).alias(baseId));
                    target = indexA.getIndex();
                } else {
                    // index_history 조회하여 마지막 인덱스, 색인 완료한 인덱스 검색.
                    QueryBuilder queryBuilder = new BoolQueryBuilder()
                            .must(new MatchQueryBuilder("jobType", "FULL_INDEX"))
                            .must(new MatchQueryBuilder("status", "SUCCESS"))
                            .should(new MatchQueryBuilder("index", indexA.getIndex()))
                            .should(new MatchQueryBuilder("index", indexB.getIndex()))
                            .minimumShouldMatch(1);
                    SearchRequest searchRequest = new SearchRequest()
                            .indices(indexHistory)
                            .source(new SearchSourceBuilder().query(queryBuilder)
                                    .size(1)
                                    .from(0)
                                    .sort("endTime", SortOrder.DESC)
                            );
                    SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
                    SearchHit[] searchHits = searchResponse.getHits().getHits();

                    if (searchHits.length == 1) {
                        // index_history 조회하여 대상 찾음.
                        Map<String, Object> source = searchHits[0].getSourceAsMap();
                        String targetIndex = (String) source.get("index");

                        if (indexA.getIndex().equals(targetIndex)) {
                            addIndex = indexA;
                            removeIndex = indexB;
                        } else {
                            addIndex = indexB;
                            removeIndex = indexA;
                        }

                        request.addAliasAction(new IndicesAliasesRequest.
                                AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                                .index(addIndex.getIndex()).alias(baseId));
                        request.addAliasAction(new IndicesAliasesRequest.
                                AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                                .index(removeIndex.getIndex()).alias(baseId));

                        target = addIndex.getIndex();
                    } else {
                        // default
                        request.addAliasAction(new IndicesAliasesRequest.
                                AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                                .index(indexA.getIndex()).alias(baseId));

                        target = indexA.getIndex();
                    }
                }

                // 교체
                client.indices().updateAliases(request, RequestOptions.DEFAULT);
            }

            changeRefreshInterval(clusterId, collection, target);
        }
    }

    public IndexingStatus reindex(UUID clusterId, Collection collection, boolean autoRun, IndexStep step) throws IndexingJobFailureException, IOException {
        return reindex(clusterId, collection, autoRun, step, new ArrayDeque<>());
    }

    public IndexingStatus reindex(UUID clusterId, Collection collection, boolean autoRun, IndexStep step, Queue<IndexStep> nextStep) throws IndexingJobFailureException, IOException {
        IndexingStatus indexingStatus = new IndexingStatus();
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            Collection.Index indexA = collection.getIndexA();
            Collection.Index indexB = collection.getIndexB();

            // 1. 대상 인덱스 찾기. source, dest
            logger.info("clusterId: {}, baseId: {}, 대상 인덱스 찾기", clusterId, collection.getBaseId());
            Collection.Index destIndex = getTargetIndex(client, collection.getBaseId(), indexA, indexB);
            Collection.Index sourceIndex = null;
            if (indexA.getIndex().equals(destIndex.getIndex())) {
                sourceIndex = indexB;
            } else {
                sourceIndex = indexA;
            }

            // 2. 인덱스 설정 변경.
            logger.info("{} 인덱스 설정 변경", destIndex);
            editPreparations(client, collection, destIndex);

            // 3. 타겟 인덱스 삭제
            boolean deleteConfirm = deleteIndex(clusterId, destIndex.getIndex());

            // 4. 타겟 인덱스 삭제가 정상
            if(deleteConfirm) {
                // 인덱스 설정
                logger.debug("indexingSettings:{}", indexing);

                // 인덱스 생성
                boolean createConfirm = createIndex(clusterId, destIndex.getIndex(), indexing);

                // 인덱스 생성이 정상
                if(createConfirm) {
                    // 런처 파라미터 변환작업
                    logger.info("{} 런처 파라미터 변환 작업", destIndex);
                    Collection.Launcher launcher = collection.getLauncher();
                    Map<String, Object> body = convertRequestParams(launcher.getYaml());

                    // reindex에 필요한 파라미터 변환
                    // reindex 설정값 default
                    // 배치 사이즈 default : 1000
                    int reindexBatchSize = 1000;
                    // sliced-scroll 슬라이스를 사용 (병렬) default : 1
                    int reindexSlices = 1;
                    float reindexRequestPerSec = -1; // 수정한 부분

                    if(body.get("reindexBatchSize") != null) {
                        try{
                            reindexBatchSize = Integer.parseInt(body.get("reindexBatchSize").toString());
                        }catch (NumberFormatException e){
                            logger.info("{}", e);
                        }
                    }

                    if(body.get("reindexSlices") != null) {
                        try{
                            reindexSlices = Integer.parseInt(body.get("reindexSlices").toString());
                        }catch (NumberFormatException e){
                            logger.info("{}", e);
                        }
                    }

                    if(body.get("reindexRequestPerSec") != null) { // 추가
                        try{
                            reindexRequestPerSec = Float.parseFloat(body.get("reindexRequestPerSec").toString());
                        }catch (NumberFormatException e){
                            logger.info("{}", e);
                        }
                    }

                    logger.debug("reindexBatchSize:{}, reindexSlices:{}, reindexRequestPerSecond:{}", reindexBatchSize, reindexSlices, reindexRequestPerSec);

                    String taskId = reformReindexing(client, collection.getBaseId(), sourceIndex.getIndex(), destIndex.getIndex(), reindexBatchSize, reindexRequestPerSec);

                    indexingStatus.setClusterId(clusterId);
                    indexingStatus.setIndex(destIndex.getIndex());
                    indexingStatus.setStartTime(System.currentTimeMillis());
                    indexingStatus.setTaskId(taskId);
                    indexingStatus.setAutoRun(autoRun);
                    indexingStatus.setNextStep(nextStep);
                    indexingStatus.setCurrentStep(step);
                    indexingStatus.setRetry(50);
                    indexingStatus.setCollection(collection);
                }
            }
        } catch (Exception e) {
            logger.error("", e);
            // Connection Timeout 히스토리 남기기
            addIndexHistoryException(clusterId, collection, e.getMessage());
            throw new IndexingJobFailureException(e);
        }
        return indexingStatus;
    }

    public boolean makeReadOnlyIndex(RestHighLevelClient client, String index, boolean flag) throws IOException, InterruptedException {
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest();
        updateSettingsRequest.indices(index);
        updateSettingsRequest.settings(Settings.builder()
                .put("index.blocks.write", flag)
                .build());
        AcknowledgedResponse response = client.indices().putSettings(updateSettingsRequest, RequestOptions.DEFAULT);
        boolean ack = response.isAcknowledged();
        while (ack) {
            response = client.indices().putSettings(updateSettingsRequest, RequestOptions.DEFAULT);
            ack = response.isAcknowledged();
            Thread.sleep(500);
        }
        return ack;
    }

    public boolean cloneIndexing(RestHighLevelClient client, String sourceIndex, String cloneIndex) throws IOException {
        ResizeRequest cloneRequest = new ResizeRequest(sourceIndex, cloneIndex);
        cloneRequest.setResizeType(ResizeType.CLONE);
        cloneRequest.timeout(TimeValue.timeValueMinutes(10));
        // 명시적으로 레플리카 0으로 셋팅...? 이건 확인 해봐야 할듯
        cloneRequest.getTargetIndexRequest().settings(
                Settings.builder()
                        .put("index.number_of_replicas", 0) // 레플리카 0
                        .put("index.blocks.write", true ) // 읽기 전용
        );

        // sync 방식
        ResizeResponse cloneResponse = client.indices().clone(cloneRequest, RequestOptions.DEFAULT);
        // async 방식
        // client.indices().cloneAsync(request, RequestOptions.DEFAULT,listener);

        // 정상적으로 클론 되었는지 확인
        boolean acknowledged = cloneResponse.isAcknowledged(); // 모든 노드가 요청 승인 되었는지
        boolean shardsAcked = cloneResponse.isShardsAcknowledged(); // 시간이 초과되기 전에 인덱스의 각 샤드에 대해 필요한 수의 샤드 복사본이 시작되었는지 여부
        return acknowledged;
    }

    public boolean splitIndexing(RestHighLevelClient client, String cloneIndex, String splitIndex) throws IOException, InterruptedException {
        ResizeRequest splitRequest = new ResizeRequest(cloneIndex, splitIndex);
        splitRequest.setResizeType(ResizeType.SPLIT);
        splitRequest.timeout(TimeValue.timeValueMinutes(1));

        // 일단 40개로 해보자
        splitRequest.getTargetIndexRequest().settings(
                Settings.builder()
                        .put("index.number_of_shards", 40) // 레플리카 0
                        .put("index.blocks.write", true ) // 읽기 전용
        );
        client.indices().split(splitRequest, RequestOptions.DEFAULT);

        // cluster level 체크...
        // 정상적으로 전부 split & relocating 되었는지  확인
        // 예시: GET /_cluster/health/s-prod-split?level=shards
        boolean check = false;
        while(!check){
            ClusterHealthRequest request = new ClusterHealthRequest(splitIndex);
            request.level(ClusterHealthRequest.Level.SHARDS);
            ClusterHealthResponse response = client.cluster().health(request, RequestOptions.DEFAULT);

            if(response.getRelocatingShards() > 0){
                Thread.sleep(1000);
            }else{
                check = true;
            }
        }

        return check;
    }

    public String reformReindexing(RestHighLevelClient client, String baseId, String sourceIndex, String destIndex, int reindexBatchSize, float reindexRequestPerSec) throws IOException, InterruptedException {
        String cloneIndex = baseId + "-clone";
        String splitIndex = sourceIndex + "-split";


        // TODO: 인덱스 클론, 스플릿 시 시간 측정 필요
        // 클론, 스플릿하기 전에 sourceIndex를 읽기전용 인덱스로 변경 필요
        makeReadOnlyIndex(client, sourceIndex, true);

        // 1. 원본 인덱스를 clone
        // cloneIndexing(client, sourceIndex, cloneIndex);

        // 2. 클론된 인덱스를 split하여 primary shard 수를 늘림
        // splitIndexing(client, cloneIndex, splitIndex);
        splitIndexing(client, sourceIndex, splitIndex);

        //  3. split된 인덱스를 source index로 삼아 reindex, reindex 명령에 "?slices=auto"를 지정
        // reindex request 설정 세팅
        ReindexRequest reindexRequest = new ReindexRequest();
        if(reindexRequestPerSec > 0) reindexRequest.setRequestsPerSecond(reindexRequestPerSec); // 추가
        reindexRequest.setSourceIndices(splitIndex);
        reindexRequest.setDestIndex(destIndex);
        reindexRequest.setRefresh(false);
        reindexRequest.setSourceBatchSize(reindexBatchSize);

        // reindex 호출
        TaskSubmissionResponse reindexSubmission = client.submitReindexTask(reindexRequest, RequestOptions.DEFAULT);

        // 작업 번호
        String taskId = reindexSubmission.getTask();
        logger.info("taskId : {} - source : {} -> dest : {}", taskId, sourceIndex, destIndex);

        makeReadOnlyIndex(client, sourceIndex, false);

        return taskId;
    }

    public void stopReindexing(UUID clusterId, Collection collection, IndexingStatus indexingStatus) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            Collection.Index indexA = collection.getIndexA();
            Collection.Index indexB = collection.getIndexB();

            Collection.Index index = getTargetIndex(collection.getBaseId(), indexA, indexB);
            // 취소 요청
            Request request = new Request("POST", String.format("/_tasks/%s/_cancel", indexingStatus.getTaskId()));
            request.addParameter("format", "json");
            Response response = client.getLowLevelClient().performRequest(request);
            String responseBodyString = EntityUtils.toString(response.getEntity());
            Map<String, Object> entityMap = new Gson().fromJson(responseBodyString, Map.class);
            //logger.info("entityMap : {}", entityMap);
            index.setStatus("STOP");
            logger.info("stop reindexing : {}", index.getIndex());
        }
    }

    private Collection.Index getTargetIndex(String baseId, Collection.Index indexA, Collection.Index indexB) {
        Collection.Index index;
        // 인덱스에 대한 alias를 확인
        if (indexA.getAliases().size() == 0 && indexB.getAliases().size() == 0) {
            index = indexA;
        } else if (indexA.getAliases().get(baseId) != null) {
            index = indexB;
        } else if (indexB.getAliases().get(baseId) != null) {
            index = indexA;
        } else {
            index = indexA;
        }
        logger.debug("choice Index: {}", index.getIndex());
        return index;
    }


    private Collection.Index getTargetIndex(RestHighLevelClient client, String baseId, Collection.Index indexA, Collection.Index indexB) {
        Collection.Index index = indexA;
        // 인덱스에 대한 alias를 확인
        if (indexA.getAliases().size() == 0 && indexB.getAliases().size() == 0) {
            return index;
        }

        try{
            RestClient lowLevelClient = client.getLowLevelClient();
            Request request = new Request("GET", "_cat/aliases");
            request.addParameter("format", "json");
            Response response = lowLevelClient.performRequest(request);
            String entityString = EntityUtils.toString(response.getEntity());
            List<Map<String, Object>> entityMap = new Gson().fromJson(entityString, List.class);

            for(Map<String, Object> item : entityMap){
                if(item.get("alias").equals(baseId)){
                    String currentIndex = (String) item.get("index");
                    String suffix = currentIndex.substring(currentIndex.length()-2);

                    if(suffix.equals("-a")){
                        index = indexB;
                    }else if(suffix.equals("-b")){
                        index = indexA;
                    }else{
                        index = indexA;
                    }
                    break;
                }
            }

        }catch (IOException e){
            logger.error("{}", e);
        }

        return index;
    }

    private void editPreparations(RestHighLevelClient client, Collection collection, Collection.Index index) throws IOException {
        // 인덱스 존재하지 않기 때문에 생성해주기.
        // 인덱스 템플릿이 존재하기 때문에 맵핑 설정 패쓰

        if (index.getUuid() == null) {
            boolean isAcknowledged;
            logger.info("ES 인덱스 없을 시, indexing settings >>> {}", indexing);
            isAcknowledged = client.indices().create(new CreateIndexRequest(index.getIndex()).settings(indexing), RequestOptions.DEFAULT).isAcknowledged();
            logger.debug("create settings : {} ", isAcknowledged);
        } else {
            // 기존 인덱스가 존재하기 때문에 셋팅 설정만 변경함.
            // settings에 index.routing.allocation.include._exclude=search* 호출
            // refresh interval: -1로 변경. 색인도중 검색노출하지 않음. 성능향상목적.
            boolean isAcknowledged;
            logger.info("ES 인덱스 존재 시, indexing settings >>> {}", indexing);
            isAcknowledged = client.indices().putSettings(new UpdateSettingsRequest().indices(index.getIndex()).settings(indexing), RequestOptions.DEFAULT).isAcknowledged();
            logger.debug("edit settings : {} ", isAcknowledged);
        }
    }

    public Map<String, Object> convertRequestParams(String yamlStr) throws IndexingJobFailureException {
        Map<String, Object> convert = new HashMap<>(params);
//        default param mixed
//        convert.putAll(params);
        try {
            Map<String, Object> tmp = new Yaml().load(yamlStr);
            if (tmp != null) {
                convert.putAll(tmp);
            }
        } catch (ClassCastException | NullPointerException e) {
            throw new IndexingJobFailureException("invalid yaml");
        }
        return convert;
    }

    public void stopIndexing(String scheme, String host, int port, String jobId) {
        try {
            indexerJobManager.stop(UUID.fromString(jobId));
        } catch (Exception ignore) {  }
        try {
            restTemplate.exchange(new URI(String.format("%s://%s:%d/async/stop?id=%s", scheme, host, port, jobId)),
                    HttpMethod.PUT,
                    new HttpEntity(new HashMap<>()),
                    String.class
            );
        } catch (Exception ignore) { }
    }

    public void subStart(String scheme, String host, int port, String jobId, String groupSeq, boolean isExtIndexer) {
        if (isExtIndexer) {
            logger.info(">>>>> Ext call sub_start: id: {}, groupSeq: {}", jobId, groupSeq);
            try {
                restTemplate.exchange(new URI(String.format("%s://%s:%d/async/%s/sub_start?groupSeq=%s", scheme, host, port, jobId, groupSeq)),
                        HttpMethod.PUT,
                        new HttpEntity(new HashMap<>()),
                        String.class
                );
            } catch (Exception e) {
                logger.error("", e);
            }
        } else {
            try {
                logger.info(">>>>> Local call sub_start: id: {}, groupSeq: {}", jobId, groupSeq);
                Job job = indexerJobManager.status(UUID.fromString(jobId));
                job.getGroupSeq().add(Integer.parseInt(groupSeq));
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    //check:reindex
    public boolean deleteIndex(UUID clusterId, String index) throws IOException {
        boolean isDeleted = false;
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            // 인덱스 삭제 요청
            DeleteIndexRequest request = new DeleteIndexRequest(index);
//            AcknowledgedResponse deleteIndexResponse = client.indices().delete(request, RequestOptions.DEFAULT);
//            flag = deleteIndexResponse.isAcknowledged();

            // 아무것도 하지 않음. 체크는 아래에서 진행.
            client.indices().deleteAsync(request, RequestOptions.DEFAULT, new ActionListener<AcknowledgedResponse>() {
                @Override
                public void onResponse(AcknowledgedResponse acknowledgedResponse) { }
                @Override
                public void onFailure(Exception e) { }
            });

            // 인덱스 삭제 여부 체크
            while (true) {
                GetIndexRequest checkRequest = new GetIndexRequest("*");
                GetIndexResponse response = client.indices().get(checkRequest, RequestOptions.DEFAULT);
                String[] indices = response.getIndices();

                // 해당 index가 없다면 -> isDelete = true
                // 해당 index가 있다면 -> isDelete = false
                for(String s : indices){
                    if(s.equals(index)){
                        // 아직까지 삭제 안됨
                        isDeleted = false;
                        break;
                    }else{
                        isDeleted = true;
                    }
                }

                if(isDeleted){
                    break;
                }else{
                    sleep(500);
                }
            }
        }catch (Exception e){
            logger.error("", e);
        }
        return isDeleted;
    }

    //check:reindex
    public boolean createIndex(UUID clusterId, String index, Map<String, ?> settings) throws IOException {
        boolean flag = false;
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            CreateIndexRequest request = new CreateIndexRequest(index);
            request.settings(settings);
            AcknowledgedResponse createIndexResponse = client.indices().create(request, RequestOptions.DEFAULT);
            flag = createIndexResponse.isAcknowledged();
        } catch (Exception e) {
            logger.error("", e);
        }
        return flag;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Map<String, Object> getIndexing() {
        return indexing;
    }

    public void setIndexing(Map<String, Object> indexing) {
        this.indexing = indexing;
    }

    public Map<String, Object> getPropagate() {
        return propagate;
    }

    public void setPropagate(Map<String, Object> propagate) {
        this.propagate = propagate;
    }

    public void setRefreshInterval(String refresh_interval) {
        this.propagate.put("refresh_interval", refresh_interval);
    }

    public String getRefreshInterval() {
        return (String) this.propagate.get("refresh_interval");
    }

    public Map<String,Object> getPropagateSettings() {
        return this.propagate;
    }

    public Map<String,Object> getIndexingSettings() {
        return this.indexing;
    }

    public void setPropagateSettings(Map<String, Object> settings) {
        this.propagate = settings;
    }

    public void setIndexingSettings(Map<String, Object> settings) {
        this.indexing = settings;
    }
}
