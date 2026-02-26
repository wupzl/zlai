package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentSummary {
    private String docId;
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
