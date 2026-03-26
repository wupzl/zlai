package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.service.RagAsyncService;
import com.harmony.backend.ai.rag.service.RagService;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
@RequiredArgsConstructor
public class RagAsyncServiceImpl implements RagAsyncService {

    private final RagService ragService;
    @Qualifier("ragTaskExecutor")
    private final Executor ragTaskExecutor;

    @Override
    public CompletableFuture<String> ingest(Long userId, String title, String content) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            ragTaskExecutor.execute(() -> {
                try {
                    future.complete(ragService.ingest(userId, title, content));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(new IllegalStateException("RAG async executor is busy", ex));
        }
        return future;
    }
}
