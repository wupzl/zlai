package com.harmony.backend.ai.rag.controller.request;

import lombok.Data;

@Data
public class RagMarkdownIngestRequest {
    private String title;
    private String markdownContent;
    private String sourcePath;
}

