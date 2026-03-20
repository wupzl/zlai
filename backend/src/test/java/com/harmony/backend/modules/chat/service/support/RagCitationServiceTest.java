package com.harmony.backend.modules.chat.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.modules.chat.service.support.model.RagCitationDiagnostics;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagCitationServiceTest {

    private final RagProperties ragProperties = new RagProperties();
    private final RagCitationService service = new RagCitationService(new ObjectMapper(), ragProperties);

    @Test
    void deriveCitations_returnsTopEvidenceWithMetadata() {
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "Context",
                List.of(
                        new RagChunkMatch(
                                "doc-1",
                                "CAP theorem states that a distributed system can only guarantee two of consistency, availability, and partition tolerance.",
                                0.92,
                                "{\"title\":\"distributed-systems.md\",\"sourcePath\":\"notes/distributed-systems.md\",\"headings\":[\"Distributed Systems\",\"CAP Theorem\"]}"
                        ),
                        new RagChunkMatch(
                                "doc-2",
                                "Bloom filters are probabilistic data structures for membership checks.",
                                0.70,
                                "{\"title\":\"probabilistic-data-structures.md\"}"
                        )
                )
        );

        List<RagCitation> citations = service.deriveCitations(
                "CAP theorem explains the trade-off between consistency, availability, and partition tolerance.",
                ragEvidence
        );

        assertThat(citations).hasSize(1);
        RagCitation citation = citations.get(0);
        assertThat(citation.getDocId()).isEqualTo("doc-1");
        assertThat(citation.getTitle()).isEqualTo("distributed-systems.md");
        assertThat(citation.getSourcePath()).isEqualTo("notes/distributed-systems.md");
        assertThat(citation.getHeadings()).containsExactly("Distributed Systems", "CAP Theorem");
        assertThat(citation.getExcerpt()).contains("CAP theorem");
        assertThat(citation.getCitationScore()).isPositive();
    }

    @Test
    void buildDiagnostics_summarizesSelectedCitationEvidence() {
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "Context",
                List.of(
                        new RagChunkMatch(
                                "doc-1",
                                "CAP theorem states that a distributed system can only guarantee two of consistency, availability, and partition tolerance.",
                                0.92,
                                "{\"title\":\"distributed-systems.md\",\"sourcePath\":\"notes/distributed-systems.md\",\"headings\":[\"Distributed Systems\",\"CAP Theorem\"]}"
                        )
                )
        );
        List<RagCitation> citations = service.deriveCitations(
                "CAP theorem explains the trade-off between consistency, availability, and partition tolerance.",
                ragEvidence
        );

        RagCitationDiagnostics diagnostics = service.buildDiagnostics(ragEvidence, citations);

        assertThat(diagnostics.getQuery()).isEqualTo("Explain CAP theorem");
        assertThat(diagnostics.getEvidenceCount()).isEqualTo(1);
        assertThat(diagnostics.getSelectedCitationCount()).isEqualTo(1);
        assertThat(diagnostics.getSelectedDocIds()).containsExactly("doc-1");
        assertThat(diagnostics.getSelectedTitles()).containsExactly("distributed-systems.md");
        assertThat(diagnostics.getCitationScores()).hasSize(1);
    }

    @Test
    void deriveCitations_returnsEmptyWhenEvidenceDisabled() {
        assertThat(service.deriveCitations("Any answer", ResolvedRagEvidence.disabled())).isEmpty();
    }

    @Test
    void deriveCitations_respectsConfiguredMaxCitationCount() {
        ragProperties.getGrounding().setMaxCitationCount(2);
        ResolvedRagEvidence ragEvidence = ResolvedRagEvidence.enabled(
                "Explain CAP theorem",
                "Context",
                List.of(
                        new RagChunkMatch(
                                "doc-1",
                                "CAP theorem discusses consistency availability partition tolerance trade offs.",
                                0.95,
                                "{\"title\":\"distributed-1.md\",\"sourcePath\":\"notes/distributed-1.md\",\"headings\":[\"CAP Theorem\"]}"
                        ),
                        new RagChunkMatch(
                                "doc-2",
                                "Consistency availability and partition tolerance are central to CAP theorem discussions.",
                                0.91,
                                "{\"title\":\"distributed-2.md\",\"sourcePath\":\"notes/distributed-2.md\",\"headings\":[\"CAP Tradeoffs\"]}"
                        ),
                        new RagChunkMatch(
                                "doc-3",
                                "Distributed systems notes again cover CAP theorem consistency availability and partitions.",
                                0.89,
                                "{\"title\":\"distributed-3.md\",\"sourcePath\":\"notes/distributed-3.md\",\"headings\":[\"Distributed Systems\"]}"
                        )
                )
        );

        List<RagCitation> citations = service.deriveCitations(
                "CAP theorem explains consistency availability and partition tolerance trade offs in distributed systems.",
                ragEvidence
        );

        assertThat(citations).hasSize(2);
    }
}
