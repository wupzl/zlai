package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagChunkMatch {
    private String docId;
    private String content;
    private double score;
}
