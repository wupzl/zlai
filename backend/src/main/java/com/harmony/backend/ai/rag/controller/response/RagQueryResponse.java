package com.harmony.backend.ai.rag.controller.response;

import com.harmony.backend.ai.rag.model.RagChunkMatch;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {
    private String context;
    private List<RagChunkMatch> matches;
}
