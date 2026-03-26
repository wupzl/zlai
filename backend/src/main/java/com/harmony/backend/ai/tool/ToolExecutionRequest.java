package com.harmony.backend.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private String toolKey;
    private String input;
    private String model;
    private String executionId;
    private String stepKey;

    public ToolExecutionRequest(String toolKey, String input, String model) {
        this(toolKey, input, model, null, null);
    }
}
