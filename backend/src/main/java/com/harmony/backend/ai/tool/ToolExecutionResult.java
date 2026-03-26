package com.harmony.backend.ai.tool;

import com.harmony.backend.ai.runtime.AgentExecutionResultView;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class ToolExecutionResult implements AgentExecutionResultView {
    private boolean success;
    private String output;
    private String error;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private List<String> usedTools;
    private Map<String, Object> metadata;

    public ToolExecutionResult(boolean success,
                               String output,
                               String error,
                               String model,
                               Integer promptTokens,
                               Integer completionTokens) {
        this(success, output, error, model, promptTokens, completionTokens, List.of(), Map.of());
    }

    public ToolExecutionResult(boolean success,
                               String output,
                               String error,
                               String model,
                               Integer promptTokens,
                               Integer completionTokens,
                               List<String> usedTools,
                               Map<String, Object> metadata) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.usedTools = usedTools == null ? List.of() : usedTools;
        this.metadata = metadata == null ? Map.of() : metadata;
    }

    public static ToolExecutionResult ok(String output) {
        return new ToolExecutionResult(true, output, null, null, null, null, List.of(), Map.of());
    }

    public static ToolExecutionResult ok(String output, String model, Integer promptTokens, Integer completionTokens) {
        return new ToolExecutionResult(true, output, null, model, promptTokens, completionTokens, List.of(), Map.of());
    }

    public static ToolExecutionResult ok(String output,
                                         String model,
                                         Integer promptTokens,
                                         Integer completionTokens,
                                         List<String> usedTools,
                                         Map<String, Object> metadata) {
        return new ToolExecutionResult(true, output, null, model, promptTokens, completionTokens, usedTools, metadata);
    }

    public static ToolExecutionResult fail(String error) {
        return new ToolExecutionResult(false, null, error, null, null, null, List.of(), Map.of());
    }

    @Override
    public List<String> getUsedTools() {
        return usedTools == null ? List.of() : usedTools;
    }
}
