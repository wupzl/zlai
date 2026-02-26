package com.harmony.backend.ai.rag.controller.request;

import lombok.Data;

@Data
public class RagMarkdownFileIngestRequest {
    private String title;
    private String filePath;
}

