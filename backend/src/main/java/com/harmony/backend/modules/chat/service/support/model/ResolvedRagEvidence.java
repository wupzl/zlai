package com.harmony.backend.modules.chat.service.support.model;

import com.harmony.backend.ai.rag.model.RagChunkMatch;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ResolvedRagEvidence {
    private boolean enabled;
    private String query;
    private String context;
    private List<RagChunkMatch> matches;

    public static ResolvedRagEvidence disabled() {
        return new ResolvedRagEvidence(false, "", "", List.of());
    }

    public static ResolvedRagEvidence enabled(String query, String context, List<RagChunkMatch> matches) {
        return new ResolvedRagEvidence(true, query == null ? "" : query, context == null ? "" : context,
                matches == null ? List.of() : List.copyOf(matches));
    }

    public boolean hasContext() {
        return context != null && !context.isBlank();
    }

    public boolean hasMatches() {
        return matches != null && !matches.isEmpty();
    }
}
