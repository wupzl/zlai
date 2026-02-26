package com.harmony.backend.ai.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {
    private int vectorSize = 256;
    private int defaultTopK = 5;
    private int chunkSize = 800;
    private int chunkOverlap = 100;
    private final Search search = new Search();

    private final Datasource datasource = new Datasource();

    @Data
    public static class Search {
        private String strategy = "mmr";
        private double minScore = 0.2;
        private double mmrLambda = 0.7;
        private int mmrCandidateMultiplier = 4;
    }

    @Data
    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "org.postgresql.Driver";
    }
}
