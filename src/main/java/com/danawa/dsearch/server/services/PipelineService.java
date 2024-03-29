package com.danawa.dsearch.server.services;

import com.danawa.dsearch.server.config.ElasticsearchFactory;
import com.danawa.dsearch.server.entity.DictionarySetting;
import com.danawa.dsearch.server.utils.JsonUtils;
import com.google.gson.Gson;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.*;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.search.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class PipelineService {
    private static Logger logger = LoggerFactory.getLogger(PipelineService.class);

    private final ElasticsearchFactory elasticsearchFactory;

    public PipelineService(ElasticsearchFactory elasticsearchFactory) {
        this.elasticsearchFactory = elasticsearchFactory;
    }

    public Response getPipeLineLists(UUID clusterId) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            RestClient restClient = client.getLowLevelClient();
            Request request = new Request("GET", "_ingest/pipeline?pretty");
            Response response = restClient.performRequest(request);
            return response;
        }
    }

    public Response testPipeline(UUID clusterId, String name, String body, boolean isDetail) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            String detail = "";
            RestClient restClient = client.getLowLevelClient();
            if(isDetail){
                detail = "?verbose";
            }
            Request request = new Request("POST", "_ingest/pipeline/" + name +"/_simulate" + detail);
            request.setJsonEntity(body);
            Response response = restClient.performRequest(request);
            return response;
        }
    }

    public Response addPipeLine(UUID clusterId, String name, String body) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            RestClient restClient = client.getLowLevelClient();
            Request request = new Request("PUT", "_ingest/pipeline/" + name);
            request.setJsonEntity(body);
            Response response = restClient.performRequest(request);
            return response;
        }
    }

    public Response getPipeLine(UUID clusterId, String name) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            RestClient restClient = client.getLowLevelClient();
            Request request = new Request("GET", "_ingest/pipeline/" + name);
            Response response = restClient.performRequest(request);
            return response;
        }
    }

    public Response deletePipeLine(UUID clusterId, String name) throws IOException {
        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)) {
            RestClient restClient = client.getLowLevelClient();
            Request request = new Request("DELETE", "_ingest/pipeline/" + name);
            Response response = restClient.performRequest(request);
            return response;
        }
    }

    public String download(UUID clusterId, Map<String, Object> message) throws IOException{
        // 1. 파이프라인 조회
        Response response = getPipeLineLists(clusterId);

        // 2. 파이프라인 json 형태로 변경
        StringBuffer sb = new StringBuffer();
        String pipelines = EntityUtils.toString(response.getEntity());

        Gson gson = JsonUtils.createCustomGson();
        Map<String, Object> pipelineMap = gson.fromJson(pipelines, Map.class);
        Map<String, Object> result = new HashMap<>();
        List<String> list = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for(String key : pipelineMap.keySet()){
            if(key.startsWith("xpack")){
                keys.add(key);
            }
        }

        for(String key: keys){
            pipelineMap.remove(key);
        }

        for(String key : pipelineMap.keySet()){
            list.add(key);
        }

        result.put("result", true);
        result.put("count", pipelineMap.size());
        result.put("list", list);

        message.put("pipeline", result);
        return gson.toJson(pipelineMap);
    }
}
