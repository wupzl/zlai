package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagChunkCandidate {
    private String docId;
    private String content;
    private float[] embedding;
    private double distance;
}
