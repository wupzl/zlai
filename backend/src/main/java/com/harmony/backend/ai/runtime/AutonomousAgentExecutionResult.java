package com.harmony.backend.ai.runtime;

import com.harmony.backend.ai.agent.runtime.AgentRunStatus;

import java.util.List;

public record AutonomousAgentExecutionResult(
        boolean success,
        String output,
        String error,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        String executionId,
        String status,
        Integer stepCount,
        List<String> usedTools
) implements AgentExecutionResultView {

    public static AutonomousAgentExecutionResult completed(String output,
                                                           String model,
                                                           String executionId,
                                                           String status,
                                                           int stepCount) {
        return new AutonomousAgentExecutionResult(true, output, null, model, null, null,
                executionId, status, stepCount, List.of());
    }

    public static AutonomousAgentExecutionResult waiting(String executionId,
                                                         String model,
                                                         int stepCount,
                                                         String reason) {
        return new AutonomousAgentExecutionResult(false, null, reason, model, null, null,
                executionId, AgentRunStatus.WAITING, stepCount, List.of());
    }

    public static AutonomousAgentExecutionResult cancelled(String executionId,
                                                           String model,
                                                           int stepCount,
                                                           String reason) {
        return new AutonomousAgentExecutionResult(false, null, reason, model, null, null,
                executionId, AgentRunStatus.CANCELLED, stepCount, List.of());
    }

    public static AutonomousAgentExecutionResult failed(String error,
                                                        String model,
                                                        String executionId,
                                                        String status,
                                                        int stepCount) {
        return new AutonomousAgentExecutionResult(false, null, error, model, null, null,
                executionId, status, stepCount, List.of());
    }

    @Override
    public boolean isSuccess() {
        return success;
    }

    @Override
    public String getOutput() {
        return output;
    }

    @Override
    public String getError() {
        return error;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public Integer getPromptTokens() {
        return promptTokens;
    }

    @Override
    public Integer getCompletionTokens() {
        return completionTokens;
    }

    @Override
    public String getExecutionId() {
        return executionId;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public Integer getStepCount() {
        return stepCount;
    }

    @Override
    public List<String> getUsedTools() {
        return usedTools == null ? List.of() : usedTools;
    }
}
