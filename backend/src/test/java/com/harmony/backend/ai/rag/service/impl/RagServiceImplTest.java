package com.harmony.backend.ai.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.harmony.backend.ai.rag.model.PreparedRagChunk;
import com.harmony.backend.ai.rag.model.PreparedRagDocument;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagEvidenceResult;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.support.RagIngestPipelineService;
import com.harmony.backend.ai.rag.service.support.RagMarkdownImageService;
import com.harmony.backend.ai.rag.service.support.RagRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {

    @Mock
    private RagRepository ragRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private RagIngestPipelineService ragIngestPipelineService;
    @Mock
    private RagMarkdownImageService ragMarkdownImageService;
    @Mock
    private RagRetrievalService ragRetrievalService;

    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.setDefaultTopK(5);
        properties.setChunkTokenSize(500);
        properties.setContextMaxTokens(1800);
        properties.getSearch().setMinScore(0.2);
        properties.getSearch().setStrategy("vector-rerank");
        properties.getSearch().setMaxChunksPerDocument(2);
        ragService = new RagServiceImpl(
                ragRepository,
                new ObjectMapper(),
                embeddingService,
                properties,
                ragIngestPipelineService,
                ragMarkdownImageService,
                ragRetrievalService);
    }

    @Test
    void buildContext_returnsRenderedRetrievalContext() {
        when(ragRetrievalService.retrieve(1L, "question", 5))
                .thenReturn(List.of(
                        new RagChunkMatch("doc-1", "First evidence", 0.92, null),
                        new RagChunkMatch("doc-2", "Second evidence", 0.84, null)
                ));

        String context = ragService.buildContext(1L, "question", 5);

        assertThat(context).contains("First evidence");
        assertThat(context).contains("Second evidence");
    }

    @Test
    void resolveEvidence_returnsEmptyWhenRetrievalMisses() {
        when(ragRetrievalService.retrieve(1L, "question", 5)).thenReturn(List.of());

        RagEvidenceResult evidence = ragService.resolveEvidence(1L, "question", 5);

        assertThat(evidence.context()).isBlank();
        assertThat(evidence.matches()).isEmpty();
    }

    @Test
    void resolveEvidence_usesWholeDocumentContextForNamedSummaryQuery() {
        when(ragRetrievalService.retrieve(1L, "总结一下Java基础.md", 5))
                .thenReturn(List.of(new RagChunkMatch("doc-1", "并发基础：线程状态与线程池。", 0.91, null)));
        when(ragRepository.searchDocumentsByTitle(1L, "Java基础.md", 3))
                .thenReturn(List.of(new com.harmony.backend.ai.rag.model.RagDocumentHit(
                        "doc-1",
                        "Java基础.md",
                        "第一章：语法基础。\n\n第二章：面向对象。\n\n第三章：集合框架。\n\n第四章：IO 与并发。",
                        1.0
                )));

        RagEvidenceResult evidence = ragService.resolveEvidence(1L, "总结一下Java基础.md", 5);

        assertThat(evidence.context()).contains("[Document] Java基础.md");
        assertThat(evidence.context()).contains("集合框架");
        assertThat(evidence.matches()).hasSize(1);
        assertThat(evidence.matches().get(0).getContent()).contains("并发基础");
    }

    @Test
    void resolveEvidence_canSummarizeNamedDocumentWhenVectorRetrievalMisses() {
        when(ragRetrievalService.retrieve(1L, "总结一下Java基础.md", 5)).thenReturn(List.of());
        when(ragRepository.searchDocumentsByTitle(1L, "Java基础.md", 3))
                .thenReturn(List.of(new com.harmony.backend.ai.rag.model.RagDocumentHit(
                        "doc-1",
                        "Java基础.md",
                        "语法基础。\n\n面向对象。\n\n集合。\n\n并发。",
                        0.98
                )));

        RagEvidenceResult evidence = ragService.resolveEvidence(1L, "总结一下Java基础.md", 5);

        assertThat(evidence.context()).contains("[Document] Java基础.md");
        assertThat(evidence.context()).contains("面向对象");
        assertThat(evidence.matches()).hasSize(1);
        assertThat(evidence.matches().get(0).getDocId()).isEqualTo("doc-1");
    }

    @Test
    void ingestMarkdownWithImages_insertsInlineOcrForRemoteImageName() {
        String markdown = "Before ![remote](https://example.com/assets/pic.png) After";
        String enriched = "Before ![remote](https://example.com/assets/pic.png)\n\n[OCR: pic.png]\nOCR_TEXT\n After";
        PreparedRagChunk chunk = new PreparedRagChunk(enriched, "paragraph", List.of(), 0, 16, Map.of());
        when(ragMarkdownImageService.embedImages(anyString(), any())).thenReturn(enriched);
        when(ragIngestPipelineService.prepareMarkdown(anyString())).thenReturn(new PreparedRagDocument(enriched, List.of(chunk)));
        when(ragRepository.findActiveDocumentIdByHash(eq(1L), anyString())).thenReturn(null);
        when(ragRepository.createDocument(eq(1L), eq("note"), eq(enriched), anyString())).thenReturn("doc-1");
        when(embeddingService.embed(eq(enriched))).thenReturn(new float[]{0.1f, 0.2f});

        ragService.ingestMarkdownWithImages(1L, "note", markdown, Map.of("pic.png", new byte[]{1, 2, 3}));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragRepository).createDocument(eq(1L), eq("note"), contentCaptor.capture(), anyString());
        assertThat(contentCaptor.getValue()).contains("[OCR: pic.png]");
        assertThat(contentCaptor.getValue()).contains("OCR_TEXT");
    }
}
