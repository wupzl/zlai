package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.service.RagAsyncService;
import com.harmony.backend.ai.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class RagAsyncServiceImpl implements RagAsyncService {

    private final RagService ragService;

    @Override
    @Async("taskExecutor")
    public CompletableFuture<String> ingest(Long userId, String title, String content) {
        return CompletableFuture.completedFuture(ragService.ingest(userId, title, content));
    }
}
