package com.harmony.backend.ai.rag.service;

import java.util.concurrent.CompletableFuture;

public interface RagAsyncService {
    CompletableFuture<String> ingest(Long userId, String title, String content);
}
