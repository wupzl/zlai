package com.harmony.backend.ai.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.harmony.backend.ai.rag.model.PreparedRagChunk;
import com.harmony.backend.ai.rag.model.PreparedRagDocument;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.support.RagIngestPipelineService;
import com.harmony.backend.ai.rag.service.support.RagOcrOptimizer;
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
import static org.mockito.ArgumentMatchers.anyInt;
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
    private OcrService ocrService;
    @Mock
    private RagIngestPipelineService ragIngestPipelineService;
    @Mock
    private RagOcrOptimizer ragOcrOptimizer;

    private RagServiceImpl ragService;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.setDefaultTopK(5);
        properties.setChunkSize(800);
        properties.setChunkOverlap(100);
        properties.getSearch().setMinScore(0.2);
        properties.getSearch().setStrategy("mmr");
        properties.getSearch().setMmrLambda(0.7);
        properties.getSearch().setMmrCandidateMultiplier(4);
        ragService = new RagServiceImpl(
                ragRepository,
                new ObjectMapper(),
                embeddingService,
                properties,
                ocrService,
                ragIngestPipelineService,
                ragOcrOptimizer);
    }

    @Test
    void buildContext_trustsVectorWhenKeywordMissAndScoreHigh() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt()))
                .thenReturn(List.of(new RagChunkMatch("doc-1", "other content", 0.55, null)));
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.searchByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentDocumentContents(eq(1L), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentChunkContents(eq(1L), anyInt())).thenReturn(List.of());

        String context = ragService.buildContext(1L, "introduce flow", 5);

        assertThat(context).contains("other content");
    }

    @Test
    void buildContext_returnsEmptyWhenKeywordMissAndLowScore() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt()))
                .thenReturn(List.of(new RagChunkMatch("doc-1", "irrelevant content", 0.05, null)));
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.searchByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentDocumentContents(eq(1L), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentChunkContents(eq(1L), anyInt())).thenReturn(List.of());

        String context = ragService.buildContext(1L, "introduce flow", 5);

        assertThat(context).isBlank();
    }

    @Test
    void buildContext_usesKeywordFallbackWhenVectorEmpty() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt())).thenReturn(List.of());
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt()))
                .thenReturn(List.of("difference between stream and collection"));

        String context = ragService.buildContext(1L, "introduce flow", 5);

        assertThat(context).contains("difference between stream and collection");
    }

    @Test
    void ingestMarkdownWithImages_insertsInlineOcrForRemoteImageName() {
        String markdown = "Before ![remote](https://example.com/assets/pic.png) After";
        String enriched = "Before ![remote](https://example.com/assets/pic.png)\n\n[OCR: pic.png]\nOCR_TEXT\n After";
        PreparedRagChunk chunk = new PreparedRagChunk(enriched, "paragraph", List.of(), 0, 16);
        when(ragOcrOptimizer.shouldProcessImage(eq("pic.png"), any(byte[].class))).thenReturn(true);
        when(ocrService.extractText(any(byte[].class), eq("pic.png"), eq("image/*"))).thenReturn("OCR_TEXT");
        when(ragOcrOptimizer.cleanOcrText("OCR_TEXT")).thenReturn("OCR_TEXT");
        when(ragOcrOptimizer.isUsefulOcrText("OCR_TEXT")).thenReturn(true);
        when(ragIngestPipelineService.prepare(anyString())).thenReturn(new PreparedRagDocument(enriched, List.of(chunk)));
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
