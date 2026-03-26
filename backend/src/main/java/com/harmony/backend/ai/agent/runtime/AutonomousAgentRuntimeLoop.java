package com.harmony.backend.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AutonomousAgentRuntimeLoop {
    private static final int MAX_REPEATED_NO_PROGRESS_STATES = 2;

    private final ObjectMapper objectMapper;
    private final AgentAutonomyPolicy policy;

    public AutonomousAgentRuntimeLoop(ObjectMapper objectMapper, AgentAutonomyPolicy policy) {
        this.objectMapper = objectMapper;
        this.policy = policy;
    }

    public AutonomousCheckpoint newCheckpoint(List<TeamAgentRuntime> selectedAgents,
                                              boolean parallel,
                                              String planReason,
                                              int stepCount,
                                              int toolCallCount) {
        List<String> selectedAgentIds = selectedAgents == null ? List.of() : selectedAgents.stream()
                .map(runtime -> runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null)
                .toList();
        return new AutonomousCheckpoint(selectedAgentIds, List.of(), 0, parallel, planReason, false, stepCount, toolCallCount, 0, null);
    }

    public String writeCheckpoint(AutonomousCheckpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (Exception e) {
            return null;
        }
    }

    public AutonomousCheckpoint readCheckpoint(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(checkpointJson, AutonomousCheckpoint.class);
        } catch (Exception e) {
            return null;
        }
    }

    public AutonomousLoopOutcome executeSpecialistSlice(List<TeamAgentRuntime> selectedAgents,
                                                        AutonomousCheckpoint checkpoint,
                                                        long invocationStartNanos,
                                                        CancellationChecker cancellationChecker,
                                                        SpecialistStepExecutor stepExecutor) {
        AutonomousCheckpoint current = checkpoint == null
                ? new AutonomousCheckpoint(List.of(), List.of(), 0, false, null, false, 0, 0, 0, null)
                : checkpoint;
        List<String> outputs = new ArrayList<>(current.outputs() == null ? List.of() : current.outputs());
        int stepCount = current.stepCount();
        int toolCallCount = current.toolCallCount();

        for (int i = current.nextAgentIndex(); i < selectedAgents.size(); i++) {
            if (cancellationChecker != null && cancellationChecker.isCancelled()) {
                return AutonomousLoopOutcome.cancelled(outputs, stepCount, toolCallCount,
                        writeCheckpoint(progressedState(current, outputs, i, false, stepCount, toolCallCount)),
                        "Cancellation requested");
            }
            String budgetReason = budgetReason(stepCount + 1, toolCallCount, invocationStartNanos);
            if (budgetReason != null) {
                return boundedPause(outputs, current, i, stepCount, toolCallCount, budgetReason);
            }
            AgentStepExecutionResult result = stepExecutor.execute(selectedAgents.get(i), List.copyOf(outputs), i, stepCount + 1);
            outputs.add(result.output());
            stepCount += 1;
            toolCallCount += Math.max(0, result.toolCalls());
            String postBudgetReason = budgetReason(stepCount, toolCallCount, invocationStartNanos);
            if (postBudgetReason != null && i + 1 < selectedAgents.size()) {
                return boundedPause(outputs, current, i + 1, stepCount, toolCallCount, postBudgetReason);
            }
        }

        AutonomousCheckpoint next = progressedState(current, outputs, selectedAgents.size(), true, stepCount, toolCallCount);
        return AutonomousLoopOutcome.ready(outputs, stepCount, toolCallCount, writeCheckpoint(next));
    }

    public AutonomousLoopOutcome gateSynthesis(AutonomousCheckpoint checkpoint,
                                               long invocationStartNanos,
                                               CancellationChecker cancellationChecker) {
        AutonomousCheckpoint current = checkpoint == null
                ? new AutonomousCheckpoint(List.of(), List.of(), 0, false, null, true, 0, 0, 0, null)
                : checkpoint;
        if (cancellationChecker != null && cancellationChecker.isCancelled()) {
            return AutonomousLoopOutcome.cancelled(current.outputs(), current.stepCount(), current.toolCallCount(),
                    writeCheckpoint(current), "Cancellation requested");
        }
        String budgetReason = budgetReason(current.stepCount() + 1, current.toolCallCount(), invocationStartNanos);
        if (budgetReason != null) {
            AutonomousCheckpoint waiting = new AutonomousCheckpoint(
                    current.selectedAgentIds(),
                    current.outputs(),
                    current.nextAgentIndex(),
                    current.parallel(),
                    current.planReason(),
                    true,
                    current.stepCount(),
                    current.toolCallCount(),
                    current.noProgressCount(),
                    current.lastProgressSignature()
            );
            return boundedPause(current.outputs(), waiting, waiting.nextAgentIndex(), waiting.stepCount(), waiting.toolCallCount(), budgetReason);
        }
        return AutonomousLoopOutcome.ready(current.outputs(), current.stepCount(), current.toolCallCount(),
                writeCheckpoint(progressedState(current, current.outputs(), current.nextAgentIndex(), current.synthesisPending(),
                        current.stepCount(), current.toolCallCount())));
    }

    private AutonomousLoopOutcome boundedPause(List<String> outputs,
                                               AutonomousCheckpoint current,
                                               int nextAgentIndex,
                                               int stepCount,
                                               int toolCallCount,
                                               String budgetReason) {
        if (!policy.isWaitOnBudgetExhausted()) {
            return AutonomousLoopOutcome.cancelled(outputs, stepCount, toolCallCount,
                    writeCheckpoint(waitingState(current, outputs, nextAgentIndex, current.synthesisPending(), stepCount, toolCallCount, budgetReason)),
                    budgetReason);
        }
        AutonomousCheckpoint waiting = waitingState(current, outputs, nextAgentIndex, current.synthesisPending(), stepCount, toolCallCount, budgetReason);
        if (waiting.noProgressCount() >= MAX_REPEATED_NO_PROGRESS_STATES) {
            return AutonomousLoopOutcome.failed(outputs, stepCount, toolCallCount, writeCheckpoint(waiting),
                    "No-progress loop detected: " + budgetReason);
        }
        return AutonomousLoopOutcome.waiting(outputs, stepCount, toolCallCount, writeCheckpoint(waiting), budgetReason);
    }

    private String budgetReason(int nextStepCount, int nextToolCallCount, long invocationStartNanos) {
        if (nextStepCount > policy.getMaxStepsPerInvocation()) {
            return "Step budget exhausted";
        }
        if (nextToolCallCount > policy.getMaxToolCallsPerInvocation()) {
            return "Tool-call budget exhausted";
        }
        long elapsedMs = (System.nanoTime() - invocationStartNanos) / 1_000_000L;
        if (elapsedMs > policy.getMaxRuntimeMs()) {
            return "Runtime budget exhausted";
        }
        return null;
    }

    private AutonomousCheckpoint waitingState(AutonomousCheckpoint current,
                                              List<String> outputs,
                                              int nextAgentIndex,
                                              boolean synthesisPending,
                                              int stepCount,
                                              int toolCallCount,
                                              String budgetReason) {
        // The signature intentionally uses checkpoint-visible state only so the guard survives
        // persisted resume without reconstructing progress from logs or in-memory history.
        String signature = signature(nextAgentIndex, synthesisPending, stepCount, toolCallCount, outputs, budgetReason);
        int noProgressCount = signature.equals(current.lastProgressSignature()) ? current.noProgressCount() + 1 : 1;
        return new AutonomousCheckpoint(
                current.selectedAgentIds(),
                List.copyOf(outputs),
                nextAgentIndex,
                current.parallel(),
                current.planReason(),
                synthesisPending,
                stepCount,
                toolCallCount,
                noProgressCount,
                signature
        );
    }

    private AutonomousCheckpoint progressedState(AutonomousCheckpoint current,
                                                 List<String> outputs,
                                                 int nextAgentIndex,
                                                 boolean synthesisPending,
                                                 int stepCount,
                                                 int toolCallCount) {
        return new AutonomousCheckpoint(
                current.selectedAgentIds(),
                List.copyOf(outputs),
                nextAgentIndex,
                current.parallel(),
                current.planReason(),
                synthesisPending,
                stepCount,
                toolCallCount,
                0,
                null
        );
    }

    private String signature(int nextAgentIndex,
                             boolean synthesisPending,
                             int stepCount,
                             int toolCallCount,
                             List<String> outputs,
                             String reason) {
        String lastOutput = outputs == null || outputs.isEmpty() ? "" : safe(outputs.get(outputs.size() - 1));
        return nextAgentIndex + "|" + synthesisPending + "|" + stepCount + "|" + toolCallCount + "|" + outputs.size() + "|" + safe(reason) + "|" + lastOutput;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @FunctionalInterface
    public interface SpecialistStepExecutor {
        AgentStepExecutionResult execute(TeamAgentRuntime runtime, List<String> priorOutputs, int specialistIndex, int stepOrder);
    }

    @FunctionalInterface
    public interface CancellationChecker {
        boolean isCancelled();
    }
}

