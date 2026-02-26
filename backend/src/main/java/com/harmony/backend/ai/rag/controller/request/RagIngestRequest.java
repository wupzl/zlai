package com.harmony.backend.ai.rag.controller.request;

import lombok.Data;

@Data
public class RagIngestRequest {
    private String title;
    private String content;
}
