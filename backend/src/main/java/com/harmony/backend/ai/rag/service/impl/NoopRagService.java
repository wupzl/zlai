package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.common.response.PageResult;

import java.util.List;

public class NoopRagService implements RagService {
    @Override
    public String ingest(Long userId, String title, String content) {
        throw new IllegalStateException("RAG datasource not configured");
    }

    @Override
    public String ingestMarkdown(Long userId, String title, String markdownContent, String sourcePath) {
        throw new IllegalStateException("RAG datasource not configured");
    }

    @Override
    public String ingestMarkdownWithImages(Long userId, String title, String markdownContent,
                                           java.util.Map<String, byte[]> images) {
        throw new IllegalStateException("RAG datasource not configured");
    }

    @Override
    public List<RagChunkMatch> search(Long userId, String query, Integer topK) {
        return List.of();
    }

    @Override
    public String buildContext(Long userId, String query, Integer topK) {
        return "";
    }

    @Override
    public PageResult<RagDocumentSummary> listDocuments(Long userId, int page, int size) {
        return new PageResult<>();
    }

    @Override
    public PageResult<RagDocumentSummary> listDocumentsForAdmin(Long userId, int page, int size) {
        return new PageResult<>();
    }

    @Override
    public boolean deleteDocument(Long userId, String docId) {
        return false;
    }

    @Override
    public boolean deleteDocumentForAdmin(String docId) {
        return false;
    }
}
