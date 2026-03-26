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
    private int chunkTokenSize = 500;
    private int chunkTokenOverlap = 80;
    private int contextMaxTokens = 1800;
    private int snippetMaxTokens = 240;
    private final Search search = new Search();
    private final Grounding grounding = new Grounding();
    private final Datasource datasource = new Datasource();

    @Data
    public static class Search {
        private String strategy = "vector-rerank";
        private boolean hybridEnabled = false;
        private double minScore = 0.2;
        private double mmrLambda = 0.7;
        private int mmrCandidateMultiplier = 4;
        private double vectorWeight = 0.65;
        private double keywordWeight = 0.35;
        private int maxChunksPerDocument = 2;
        private int rerankCandidateMultiplier = 8;
        private double rerankVectorWeight = 0.72;
        private double rerankLexicalWeight = 0.28;
        private double rerankExactMatchBonus = 0.12;
    }

    @Data
    public static class Datasource {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "org.postgresql.Driver";
    }

    @Data
    public static class Grounding {
        private boolean enabled = true;
        private double minGroundedScore = 0.70;
        private double minPartialScore = 0.30;
        private int minCitationCount = 1;
        private int maxCitationCount = 3;
        private boolean allowPartialAnswer = true;
        private boolean showGroundingHint = true;
        private String policyVersion = "v1";
        private String insufficientEvidenceMessage =
                "No relevant context found in the knowledge base. Please refine your question or add documents.";
        private String partialAnswerPrefix =
                "I found limited support in the knowledge base. The following answer may be partial:\n\n";
    }
}
