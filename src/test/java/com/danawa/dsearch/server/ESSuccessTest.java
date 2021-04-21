package com.danawa.dsearch.server;

import com.danawa.dsearch.server.config.ElasticsearchFactory;
import com.google.gson.Gson;
import org.apache.http.HttpHost;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.client.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class ESSuccessTest {
//    private static Logger logger = LoggerFactory.getLogger(ESSuccessTest.class);
//
//    @Autowired
//    private ElasticsearchFactory elasticsearchFactory;
//
//    @Test
//    public void stageCheckTest() {
//        UUID clusterId = UUID.fromString("283adcc8-c7dc-4b19-a150-fcebff66751c");
//        String index = "test-a";
//        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
//            Request request = new Request("GET", String.format("/%s/_recovery", index));
//            request.addParameter("format", "json");
//            request.addParameter("filter_path", "**.shards.stage");
//            Response response = client.getLowLevelClient().performRequest(request);
//            String entityString = EntityUtils.toString(response.getEntity());
//            Map<String ,Object> entityMap = new Gson().fromJson(entityString, Map.class);
//            List<Map<String, Object>> shards = (List<Map<String, Object>>) ((Map) entityMap.get(index)).get("shards");
//            boolean done = true;
//            for (int i = 0; i < shards.size(); i++) {
//                Map<String, Object> shard = shards.get(i);
//                String stage = String.valueOf(shard.get("stage"));
//                if (!"DONE".equalsIgnoreCase(stage)) {
//                    done = false;
//                    break;
//                }
//            }
//            logger.debug("{}, {}", done, shards);
//        }catch (Exception e) {
//            logger.error("", e);
//        }
//
//    }

    @Test
    public void aliasTest() throws IOException {
        RestClientBuilder builder = RestClient.builder(new HttpHost("localhost", 9200))
                .setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                        .setConnectTimeout(10000)
                        .setSocketTimeout(10 * 60 * 1000))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultIOReactorConfig(
                        IOReactorConfig.custom()
                                .setIoThreadCount(1)
                                .build()
                ));

        RestHighLevelClient client = new RestHighLevelClient(builder);

            RestClient lowLevelClient = client.getLowLevelClient();
            Request request = new Request("GET", "_cat/aliases");
            request.addParameter("format", "json");
            Response response = lowLevelClient.performRequest(request);
            String entityString = EntityUtils.toString(response.getEntity());
            List<Map<String, Object>> entityMap = new Gson().fromJson(entityString, List.class);
        System.out.println();
    }


}
