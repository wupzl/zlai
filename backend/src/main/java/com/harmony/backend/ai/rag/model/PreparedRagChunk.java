package com.harmony.backend.ai.rag.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class PreparedRagChunk {
    private final String content;
    private final String blockType;
    private final List<String> headings;
    private final int ordinal;
    private final int tokenCount;
    private final Map<String, Object> attributes;
}