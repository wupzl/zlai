package com.harmony.backend.ai.rag.controller.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ragQueryResponse_preservesEvidenceFieldsNeededForCitationDerivation() throws Exception {
        RagChunkMatch match = new RagChunkMatch(
                "doc-1",
                "CAP theorem states that a distributed data store can provide only two of the three guarantees.",
                0.91,
                "{\"title\":\"distributed-systems.md\",\"sourcePath\":\"notes/distributed-systems.md\",\"headings\":[\"Distributed Systems\",\"CAP Theorem\"]}"
        );
        RagQueryResponse response = new RagQueryResponse(
                "Context built from retrieved chunks",
                List.of(match)
        );

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(root.path("context").asText()).isEqualTo("Context built from retrieved chunks");
        assertThat(root.path("matches")).hasSize(1);
        JsonNode serializedMatch = root.path("matches").get(0);
        assertThat(serializedMatch.path("docId").asText()).isEqualTo("doc-1");
        assertThat(serializedMatch.path("content").asText())
                .contains("CAP theorem");
        assertThat(serializedMatch.path("score").asDouble()).isEqualTo(0.91d);
        assertThat(serializedMatch.path("chunkMetadata").asText())
                .contains("\"title\":\"distributed-systems.md\"")
                .contains("\"headings\"");
    }
}
