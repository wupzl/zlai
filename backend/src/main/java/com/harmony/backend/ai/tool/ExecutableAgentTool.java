package com.harmony.backend.ai.tool;

public interface ExecutableAgentTool extends AgentTool {
    ToolExecutionResult execute(ToolExecutionRequest request);
}
