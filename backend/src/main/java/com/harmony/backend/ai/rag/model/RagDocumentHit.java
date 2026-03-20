package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentHit {
    private String docId;
    private String title;
    private String content;
    private double score;
}
