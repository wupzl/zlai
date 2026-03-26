package com.harmony.backend.ai.agent.runtime;

import java.util.List;

public record AutonomousCheckpoint(
        List<String> selectedAgentIds,
        List<String> outputs,
        int nextAgentIndex,
        boolean parallel,
        String planReason,
        boolean synthesisPending,
        int stepCount,
        int toolCallCount,
        int noProgressCount,
        String lastProgressSignature
) {
}
