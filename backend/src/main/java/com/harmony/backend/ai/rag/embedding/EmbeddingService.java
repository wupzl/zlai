package com.harmony.backend.ai.rag.embedding;

public interface EmbeddingService {
    float[] embed(String text);
}
