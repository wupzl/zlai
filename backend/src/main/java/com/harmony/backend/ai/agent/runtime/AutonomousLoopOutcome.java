package com.harmony.backend.ai.agent.runtime;

import java.util.List;

public record AutonomousLoopOutcome(
        List<String> outputs,
        int stepCount,
        int toolCallCount,
        String checkpointJson,
        String waitReason,
        boolean waiting,
        boolean cancelled,
        boolean failed
) {

    public static AutonomousLoopOutcome ready(List<String> outputs,
                                              int stepCount,
                                              int toolCallCount,
                                              String checkpointJson) {
        return new AutonomousLoopOutcome(outputs, stepCount, toolCallCount, checkpointJson, null, false, false, false);
    }

    public static AutonomousLoopOutcome waiting(List<String> outputs,
                                                int stepCount,
                                                int toolCallCount,
                                                String checkpointJson,
                                                String waitReason) {
        return new AutonomousLoopOutcome(outputs, stepCount, toolCallCount, checkpointJson, waitReason, true, false, false);
    }

    public static AutonomousLoopOutcome cancelled(List<String> outputs,
                                                  int stepCount,
                                                  int toolCallCount,
                                                  String checkpointJson,
                                                  String reason) {
        return new AutonomousLoopOutcome(outputs, stepCount, toolCallCount, checkpointJson, reason, false, true, false);
    }

    public static AutonomousLoopOutcome failed(List<String> outputs,
                                               int stepCount,
                                               int toolCallCount,
                                               String checkpointJson,
                                               String reason) {
        return new AutonomousLoopOutcome(outputs, stepCount, toolCallCount, checkpointJson, reason, false, false, true);
    }
}
