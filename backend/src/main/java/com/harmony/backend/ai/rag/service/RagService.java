package com.harmony.backend.ai.rag.service;

import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.common.response.PageResult;

import java.util.List;

public interface RagService {
    String ingest(Long userId, String title, String content);
    String ingestMarkdown(Long userId, String title, String markdownContent, String sourcePath);

    String ingestMarkdownWithImages(Long userId, String title, String markdownContent,
                                    java.util.Map<String, byte[]> images);

    List<RagChunkMatch> search(Long userId, String query, Integer topK);

    String buildContext(Long userId, String query, Integer topK);

    PageResult<RagDocumentSummary> listDocuments(Long userId, int page, int size);

    PageResult<RagDocumentSummary> listDocumentsForAdmin(Long userId, int page, int size);

    boolean deleteDocument(Long userId, String docId);

    boolean deleteDocumentForAdmin(String docId);
}
