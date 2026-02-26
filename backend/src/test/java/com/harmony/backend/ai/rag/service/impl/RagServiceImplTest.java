package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.OcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceImplTest {

    @Mock
    private RagRepository ragRepository;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private OcrService ocrService;

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
        ragService = new RagServiceImpl(ragRepository, embeddingService, properties, ocrService);
    }

    @Test
    void buildContext_trustsVectorWhenKeywordMissAndScoreHigh() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt()))
                .thenReturn(List.of(new RagChunkMatch("doc-1", "其他内容", 0.55)));
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.searchByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentDocumentContents(eq(1L), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentChunkContents(eq(1L), anyInt())).thenReturn(List.of());

        String context = ragService.buildContext(1L, "介绍一下流", 5);

        assertThat(context).contains("其他内容");
    }

    @Test
    void buildContext_returnsEmptyWhenKeywordMissAndLowScore() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt()))
                .thenReturn(List.of(new RagChunkMatch("doc-1", "无关内容", 0.05)));
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.searchByKeyword(eq(1L), anyString(), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentDocumentContents(eq(1L), anyInt())).thenReturn(List.of());
        when(ragRepository.listRecentChunkContents(eq(1L), anyInt())).thenReturn(List.of());

        String context = ragService.buildContext(1L, "介绍一下流", 5);

        assertThat(context).isBlank();
    }

    @Test
    void buildContext_usesKeywordFallbackWhenVectorEmpty() {
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ragRepository.search(eq(1L), any(float[].class), anyInt()))
                .thenReturn(List.of());
        when(ragRepository.searchChunksByKeyword(eq(1L), anyString(), anyInt()))
                .thenReturn(List.of("流和集合的差异"));

        String context = ragService.buildContext(1L, "介绍一下流", 5);

        assertThat(context).contains("流和集合的差异");
    }

    @Test
    void ingestMarkdownWithImages_insertsInlineOcrForRemoteImageName() {
        when(ragRepository.createDocument(eq(1L), anyString(), anyString())).thenReturn("doc-1");
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(ocrService.extractText(any(), anyString(), anyString())).thenReturn("OCR_TEXT");

        String markdown = "Before ![remote](https://example.com/assets/pic.png) After";
        Map<String, byte[]> images = Map.of("pic.png", new byte[]{1, 2, 3});

        ragService.ingestMarkdownWithImages(1L, "note", markdown, images);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ragRepository).createDocument(eq(1L), eq("note"), contentCaptor.capture());
        String enriched = contentCaptor.getValue();
        assertThat(enriched).contains("[OCR: pic.png]");
        assertThat(enriched).contains("OCR_TEXT");
    }
}
