package com.harmony.backend.ai.rag.service;

public interface OcrService {
    String extractText(byte[] imageBytes, String originalFilename, String contentType);
}
