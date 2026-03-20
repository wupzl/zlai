package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.GroundingTraceDiagnostics;
import com.harmony.backend.modules.chat.service.support.model.RagCitationDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingTraceServiceTest {

    private final GroundingTraceService service = new GroundingTraceService();

    @Test
    void buildsGroundedOperatorTrace() {
        GroundingAssessment assessment = GroundingAssessment.builder()
                .status("grounded")
                .groundingScore(0.91)
                .eligibleCitationCount(2)
                .fallbackReason("")
                .policyVersion("v1")
                .build();
        RagCitationDiagnostics citationDiagnostics = RagCitationDiagnostics.builder()
                .evidenceCount(3)
                .selectedCitationCount(2)
                .selectedDocIds(List.of("doc-1", "doc-2"))
                .selectedTitles(List.of("distributed-systems.md", "consensus.md"))
                .citationScores(List.of(0.95, 0.88))
                .build();

        GroundingTraceDiagnostics trace = service.build(
                "chat-1",
                "assistant-1",
                "Explain CAP theorem",
                assessment,
                citationDiagnostics
        );

        assertThat(trace.getChatId()).isEqualTo("chat-1");
        assertThat(trace.getAssistantMessageId()).isEqualTo("assistant-1");
        assertThat(trace.getQuery()).isEqualTo("Explain CAP theorem");
        assertThat(trace.getStatus()).isEqualTo("grounded");
        assertThat(trace.getGroundingScore()).isEqualTo(0.91d);
        assertThat(trace.getPolicyVersion()).isEqualTo("v1");
        assertThat(trace.isDowngraded()).isFalse();
        assertThat(trace.getEvidenceCount()).isEqualTo(3);
        assertThat(trace.getEligibleCitationCount()).isEqualTo(2);
        assertThat(trace.getSelectedCitationCount()).isEqualTo(2);
        assertThat(trace.getSelectedDocIds()).containsExactly("doc-1", "doc-2");
    }

    @Test
    void marksDowngradedTraceForInsufficientEvidence() {
        GroundingAssessment assessment = GroundingAssessment.builder()
                .status("insufficient_evidence")
                .groundingScore(0.12)
                .eligibleCitationCount(0)
                .fallbackReason("weak_evidence_overlap")
                .policyVersion("v1")
                .build();
        RagCitationDiagnostics citationDiagnostics = RagCitationDiagnostics.builder()
                .evidenceCount(1)
                .selectedCitationCount(0)
                .selectedDocIds(List.of())
                .selectedTitles(List.of())
                .citationScores(List.of())
                .build();

        GroundingTraceDiagnostics trace = service.build(
                "chat-2",
                "assistant-2",
                "Explain unknown topic",
                assessment,
                citationDiagnostics
        );

        assertThat(trace.getStatus()).isEqualTo("insufficient_evidence");
        assertThat(trace.getFallbackReason()).isEqualTo("weak_evidence_overlap");
        assertThat(trace.isDowngraded()).isTrue();
        assertThat(trace.getEvidenceCount()).isEqualTo(1);
        assertThat(trace.getEligibleCitationCount()).isZero();
        assertThat(trace.getSelectedCitationCount()).isZero();
    }
}
