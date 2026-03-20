package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingFallbackServiceTest {

    private final RagProperties ragProperties = new RagProperties();
    private final GroundingFallbackService service = new GroundingFallbackService(ragProperties);

    @Test
    void apply_keepsGroundedAnswerUnchanged() {
        String result = service.apply(
                "Grounded answer",
                GroundingAssessment.builder().status("grounded").build(),
                "No relevant context found"
        );

        assertThat(result).isEqualTo("Grounded answer");
    }

    @Test
    void apply_prefixesPartialAnswer() {
        String result = service.apply(
                "CAP theorem discusses distributed system trade-offs.",
                GroundingAssessment.builder().status("partial").build(),
                "No relevant context found"
        );

        assertThat(result).startsWith("I found limited support in the knowledge base.");
        assertThat(result).contains("CAP theorem discusses distributed system trade-offs.");
    }

    @Test
    void apply_replacesInsufficientEvidenceAnswer() {
        String result = service.apply(
                "Potentially unsafe unsupported answer",
                GroundingAssessment.builder().status("insufficient_evidence").build(),
                "No relevant context found in the knowledge base. Please refine your question or add documents."
        );

        assertThat(result)
                .isEqualTo("No relevant context found in the knowledge base. Please refine your question or add documents.");
    }
}
