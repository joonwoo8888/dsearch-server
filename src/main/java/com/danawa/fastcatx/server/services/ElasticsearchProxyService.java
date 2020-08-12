package com.danawa.fastcatx.server.services;

import com.danawa.fastcatx.server.config.ElasticsearchFactory;
import com.danawa.fastcatx.server.excpetions.ServiceException;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Service
public class ElasticsearchProxyService {
    private static Logger logger = LoggerFactory.getLogger(ElasticsearchProxyService.class);

    private ElasticsearchFactory elasticsearchFactory;

    public ElasticsearchProxyService(ElasticsearchFactory elasticsearchFactory) {
        this.elasticsearchFactory = elasticsearchFactory;
    }

    public Response proxy(UUID clusterId, HttpServletRequest request, Map<String, String> queryStringMap, byte[] body) throws IOException {
//        /elasticsearch  substring 14
        String uri = request.getRequestURI().substring(14);
        if(queryStringMap.get("proxyUri") != null) {
            uri = String.valueOf(queryStringMap.get("proxyUri"));
            queryStringMap.remove("proxyUri");
        }
        Request req = new Request(request.getMethod(), uri);
        if (queryStringMap != null) {
            Iterator<String> keys = queryStringMap.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                req.addParameter(key, queryStringMap.get(key));
            }
        }

        if (body != null) {
            req.setEntity(new NStringEntity(new String(body), ContentType.APPLICATION_JSON));
        }

        try (RestHighLevelClient client = elasticsearchFactory.getClient(clusterId)){
            return client.getLowLevelClient().performRequest(req);
        }
    }


}
