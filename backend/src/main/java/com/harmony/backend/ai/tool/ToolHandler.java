package com.harmony.backend.ai.tool;

public interface ToolHandler {
    boolean supports(String toolKey);

    ToolExecutionResult execute(ToolExecutionRequest request);
}

