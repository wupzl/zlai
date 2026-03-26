package com.harmony.backend.ai.agent.runtime;

public record AgentStepExecutionResult(String output, int toolCalls) {
}
