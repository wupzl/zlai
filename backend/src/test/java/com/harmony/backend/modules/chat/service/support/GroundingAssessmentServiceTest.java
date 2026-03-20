package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingAssessmentServiceTest {

    private final RagProperties ragProperties = new RagProperties();
    private final GroundingAssessmentService service = new GroundingAssessmentService(ragProperties);

    @Test
    void assess_returnsGrounded_whenEvidenceAndCitationOverlapAreStrong() {
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "CAP theorem explains the trade-off between consistency availability and partition tolerance.",
                List.of(new RagChunkMatch("doc-1", "CAP theorem excerpt", 0.92, null))
        );
        List<RagCitation> citations = List.of(RagCitation.builder()
                .docId("doc-1")
                .title("distributed-systems.md")
                .headings(List.of("CAP Theorem"))
                .excerpt("CAP theorem explains the trade-off between consistency availability and partition tolerance.")
                .citationScore(0.93)
                .build());

        GroundingAssessment result = service.assess(
                "CAP theorem explains the trade-off between consistency availability and partition tolerance.",
                ragEvidence,
                citations
        );

        assertThat(result.getStatus()).isEqualTo("grounded");
        assertThat(result.getGroundingScore()).isGreaterThanOrEqualTo(0.60d);
        assertThat(result.getFallbackReason()).isNull();
        assertThat(result.getEligibleCitationCount()).isEqualTo(1);
    }

    @Test
    void assess_returnsPartial_whenEvidenceOverlapIsModerate() {
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "CAP theorem discusses consistency availability and partition tolerance.",
                List.of(new RagChunkMatch("doc-1", "CAP theorem excerpt", 0.82, null))
        );
        List<RagCitation> citations = List.of(RagCitation.builder()
                .docId("doc-1")
                .title("distributed-systems.md")
                .excerpt("CAP theorem discusses consistency availability and partition tolerance.")
                .citationScore(0.66)
                .build());

        GroundingAssessment result = service.assess(
                "CAP theorem discusses distributed system trade-offs.",
                ragEvidence,
                citations
        );

        assertThat(result.getStatus()).isEqualTo("partial");
        assertThat(result.getGroundingScore()).isBetween(0.30d, 0.70d);
        assertThat(result.getFallbackReason()).isEqualTo("partial_answer_only");
    }

    @Test
    void assess_returnsInsufficientEvidence_whenNoEligibleCitationsExist() {
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "Bloom filters are probabilistic data structures.",
                List.of(new RagChunkMatch("doc-2", "Bloom filters excerpt", 0.70, null))
        );

        GroundingAssessment result = service.assess(
                "CAP theorem compares consistency availability and partition tolerance.",
                ragEvidence,
                List.of()
        );

        assertThat(result.getStatus()).isEqualTo("insufficient_evidence");
        assertThat(result.getFallbackReason()).isEqualTo("no_eligible_citations");
        assertThat(result.getEligibleCitationCount()).isZero();
    }

    @Test
    void assess_returnsInsufficientEvidence_whenNoRetrievedEvidenceExists() {
        GroundingAssessment result = service.assess(
                "Any answer",
                ResolvedRagEvidence.disabled(),
                List.of()
        );

        assertThat(result.getStatus()).isEqualTo("insufficient_evidence");
        assertThat(result.getFallbackReason()).isEqualTo("no_retrieved_evidence");
        assertThat(result.getEvidenceCount()).isZero();
    }
}
