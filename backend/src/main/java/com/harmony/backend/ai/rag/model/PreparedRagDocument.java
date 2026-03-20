package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class PreparedRagDocument {
    private final String content;
    private final List<PreparedRagChunk> chunks;
}
