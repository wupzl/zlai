package com.harmony.backend.ai.rag.service.support;

import com.harmony.backend.ai.rag.model.PreparedRagDocument;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


@Service
public class RagIngestPipelineService {

    private final RagContentCleaner ragContentCleaner;
    private final RagChunkingService ragChunkingService;

    public RagIngestPipelineService(RagContentCleaner ragContentCleaner,
                                    RagChunkingService ragChunkingService) {
        this.ragContentCleaner = ragContentCleaner;
        this.ragChunkingService = ragChunkingService;
    }

    public PreparedRagDocument prepare(String rawContent) {
        String cleaned = ragContentCleaner.cleanDocument(rawContent);
        var chunks = ragChunkingService.chunk(cleaned);
        if (!StringUtils.hasText(cleaned)) {
            cleaned = rawContent == null ? "" : rawContent.trim();
        }
        return new PreparedRagDocument(cleaned, chunks);
    }

    public PreparedRagDocument prepareMarkdown(String rawContent) {
        String cleaned = ragContentCleaner.cleanDocument(rawContent);
        var chunks = ragChunkingService.chunkMarkdown(cleaned);
        if (!StringUtils.hasText(cleaned)) {
            cleaned = rawContent == null ? "" : rawContent.trim();
        }
        if (chunks == null || chunks.isEmpty()) {
            chunks = ragChunkingService.chunk(cleaned);
        }
        return new PreparedRagDocument(cleaned, chunks);
    }
}
