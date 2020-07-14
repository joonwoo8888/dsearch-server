package com.danawa.fastcatx.server.services;

import com.danawa.fastcatx.server.config.ElasticsearchFactory;
import com.danawa.fastcatx.server.entity.JdbcRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;


@Service
public class JdbcService {

    private static Logger logger = LoggerFactory.getLogger(RankingTuningService.class);

    private String jdbcIndex;
    private final String JDBC_JSON = "jdbc.json";
    private final ElasticsearchFactory elasticsearchFactory;
    private IndicesService indicesService;

    public JdbcService(@Value("$(fastcatx.jdbc.setting)") String jdbcIndex, IndicesService indicesService, ElasticsearchFactory elasticsearchFactory) {
        this.jdbcIndex = jdbcIndex;
        this.indicesService = indicesService;
        this.elasticsearchFactory = elasticsearchFactory;
    }

    public void fetchSystemIndex(UUID clusterId) throws IOException {
        indicesService.createSystemIndex(clusterId, jdbcIndex, JDBC_JSON);
    }

    public boolean connectionTest(JdbcRequest jdbcRequest){
        boolean flag = false;
        try{
            /* MySQL */
//            Driver : com.mysql.jdbc.Driver
//            URL   : jdbc:mysql://localhost:3306/DBNAME

            /* Altibase */
//            - Driver Name : Altibase
//            - Class Name : Altibase.jdbc.driver.AltibaseDriver
//            - URL Template : jdbc:Altibase://{host}[:{port}]/{database}
//            - Default Port : 20300

            /* Oracle */
//            oracle.jdbc.driver.OracleDriver
//            jdbc:oracle:thin:@{host}:{port}/{database}

            String url = jdbcRequest.getUrl() + jdbcRequest.getAddress() + ":" + jdbcRequest.getPort() + "/" + jdbcRequest.getDB_name();
            Class.forName(jdbcRequest.getDriver());
            Connection connection = null;
            connection = DriverManager.getConnection(url, jdbcRequest.getUser(), jdbcRequest.getPassword());
            connection.close();
            flag = true;
        }catch (SQLException sqlException){
            System.out.println(sqlException);
        }catch (ClassNotFoundException classNotFoundException){
            System.out.println(classNotFoundException);
        } catch (Exception e){
            System.out.println(e);
        }
        return flag;
    }
}
