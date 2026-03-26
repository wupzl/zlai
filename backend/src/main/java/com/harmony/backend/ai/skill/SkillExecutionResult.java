package com.harmony.backend.ai.skill;

import com.harmony.backend.ai.runtime.AgentExecutionResultView;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class SkillExecutionResult implements AgentExecutionResultView {
    private boolean success;
    private String output;
    private String error;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private List<String> usedTools;
    private Map<String, Object> metadata;

    public SkillExecutionResult(boolean success,
                                String output,
                                String error,
                                String model,
                                Integer promptTokens,
                                Integer completionTokens,
                                List<String> usedTools) {
        this(success, output, error, model, promptTokens, completionTokens, usedTools, Map.of());
    }

    public SkillExecutionResult(boolean success,
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

    public static SkillExecutionResult ok(String output, String model, Integer promptTokens,
                                          Integer completionTokens, List<String> usedTools) {
        return new SkillExecutionResult(true, output, null, model, promptTokens, completionTokens, usedTools, Map.of());
    }

    public static SkillExecutionResult ok(String output, String model, Integer promptTokens,
                                          Integer completionTokens, List<String> usedTools, Map<String, Object> metadata) {
        return new SkillExecutionResult(true, output, null, model, promptTokens, completionTokens, usedTools, metadata);
    }

    public static SkillExecutionResult fail(String error) {
        return new SkillExecutionResult(false, null, error, null, null, null, List.of(), Map.of());
    }

    @Override
    public List<String> getUsedTools() {
        return usedTools == null ? List.of() : usedTools;
    }
}
