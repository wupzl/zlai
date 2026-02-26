package com.harmony.backend.ai.rag.controller.request;

import lombok.Data;

@Data
public class RagQueryRequest {
    private String query;
    private Integer topK;
}
