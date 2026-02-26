package com.harmony.backend.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private boolean success;
    private String output;
    private String error;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;

    public static ToolExecutionResult ok(String output) {
        return new ToolExecutionResult(true, output, null, null, null, null);
    }

    public static ToolExecutionResult ok(String output, String model, Integer promptTokens, Integer completionTokens) {
        return new ToolExecutionResult(true, output, null, model, promptTokens, completionTokens);
    }

    public static ToolExecutionResult fail(String error) {
        return new ToolExecutionResult(false, null, error, null, null, null);
    }
}
