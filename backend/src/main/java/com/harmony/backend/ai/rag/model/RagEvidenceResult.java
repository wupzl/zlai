package com.harmony.backend.ai.rag.model;

import java.util.List;

public record RagEvidenceResult(String context, List<RagChunkMatch> matches) {

    public static RagEvidenceResult empty() {
        return new RagEvidenceResult("", List.of());
    }

    public RagEvidenceResult {
        context = context == null ? "" : context;
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
