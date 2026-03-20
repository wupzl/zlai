package com.harmony.backend.modules.chat.controller.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundedChatPayloadContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void syncChatApiResponse_supportsAdditiveGroundingAndCitationMetadata() throws Exception {
        GroundedChatResponse responsePayload = GroundedChatResponse.builder()
                .content("CAP theorem balances consistency, availability, and partition tolerance.")
                .citations(List.of(RagCitation.builder()
                        .docId("doc-1")
                        .title("distributed-systems.md")
                        .sourcePath("distributed/distributed-systems.md")
                        .headings(List.of("CAP Theorem"))
                        .excerpt("CAP theorem states that a distributed data store can provide only two of the three guarantees...")
                        .retrievalScore(0.91)
                        .citationScore(0.93)
                        .build()))
                .grounding(GroundingAssessment.builder()
                        .status("grounded")
                        .groundingScore(0.88)
                        .evidenceCount(3)
                        .eligibleCitationCount(1)
                        .fallbackReason("")
                        .policyVersion("v1")
                        .build())
                .build();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.success(responsePayload)));

        assertThat(root.path("code").asInt()).isEqualTo(200);
        assertThat(root.path("message").asText()).isEqualTo("success");
        assertThat(root.path("data").path("content").asText())
                .isEqualTo("CAP theorem balances consistency, availability, and partition tolerance.");
        assertThat(root.path("data").path("citations")).hasSize(1);
        assertThat(root.path("data").path("citations").get(0).path("docId").asText())
                .isEqualTo("doc-1");
        assertThat(root.path("data").path("citations").get(0).path("sourcePath").asText())
                .isEqualTo("distributed/distributed-systems.md");
        assertThat(root.path("data").path("citations").get(0).path("headings")).hasSize(1);
        assertThat(root.path("data").path("grounding").path("status").asText())
                .isEqualTo("grounded");
        assertThat(root.path("data").path("grounding").path("groundingScore").asDouble())
                .isEqualTo(0.88d);
        assertThat(root.path("data").path("grounding").path("evidenceCount").asInt()).isEqualTo(3);
        assertThat(root.path("data").path("grounding").path("eligibleCitationCount").asInt()).isEqualTo(1);
        assertThat(root.path("data").path("grounding").path("policyVersion").asText()).isEqualTo("v1");
    }

    @Test
    void syncChatApiResponse_supportsPartialGroundingMetadata() throws Exception {
        GroundedChatResponse responsePayload = GroundedChatResponse.builder()
                .content("I found partial evidence about CAP theorem, but the requested comparison is incomplete.")
                .citations(List.of(RagCitation.builder()
                        .docId("doc-1")
                        .title("distributed-systems.md")
                        .excerpt("CAP theorem excerpt")
                        .retrievalScore(0.82)
                        .citationScore(0.84)
                        .build()))
                .grounding(GroundingAssessment.builder()
                        .status("partial")
                        .groundingScore(0.54)
                        .evidenceCount(2)
                        .eligibleCitationCount(1)
                        .fallbackReason("partial_answer_only")
                        .policyVersion("v1")
                        .build())
                .build();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.success(responsePayload)));

        assertThat(root.path("data").path("grounding").path("status").asText()).isEqualTo("partial");
        assertThat(root.path("data").path("grounding").path("groundingScore").asDouble()).isEqualTo(0.54d);
        assertThat(root.path("data").path("grounding").path("evidenceCount").asInt()).isEqualTo(2);
        assertThat(root.path("data").path("grounding").path("eligibleCitationCount").asInt()).isEqualTo(1);
        assertThat(root.path("data").path("grounding").path("fallbackReason").asText())
                .isEqualTo("partial_answer_only");
        assertThat(root.path("data").path("grounding").path("policyVersion").asText()).isEqualTo("v1");
        assertThat(root.path("data").path("citations")).hasSize(1);
        assertThat(root.path("data").path("content").asText()).contains("partial evidence");
    }

    @Test
    void syncChatApiResponse_supportsInsufficientEvidenceFallbackMetadata() throws Exception {
        GroundedChatResponse responsePayload = GroundedChatResponse.builder()
                .content("I could not find enough support in the knowledge base to answer confidently.")
                .citations(List.of())
                .grounding(GroundingAssessment.builder()
                        .status("insufficient_evidence")
                        .groundingScore(0.12)
                        .evidenceCount(1)
                        .eligibleCitationCount(0)
                        .fallbackReason("insufficient_evidence")
                        .policyVersion("v1")
                        .build())
                .build();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.success(responsePayload)));

        assertThat(root.path("data").path("grounding").path("status").asText())
                .isEqualTo("insufficient_evidence");
        assertThat(root.path("data").path("grounding").path("groundingScore").asDouble()).isEqualTo(0.12d);
        assertThat(root.path("data").path("grounding").path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(root.path("data").path("grounding").path("eligibleCitationCount").asInt()).isEqualTo(0);
        assertThat(root.path("data").path("grounding").path("fallbackReason").asText())
                .isEqualTo("insufficient_evidence");
        assertThat(root.path("data").path("grounding").path("policyVersion").asText()).isEqualTo("v1");
        assertThat(root.path("data").path("citations")).isEmpty();
        assertThat(root.path("data").path("content").asText())
                .contains("could not find enough support");
    }
}
