package com.harmony.backend.ai.skill;

import com.harmony.backend.ai.runtime.AgentExecutionResultView;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionResult implements AgentExecutionResultView {
    private boolean success;
    private String output;
    private String error;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private List<String> usedTools;

    public static SkillExecutionResult ok(String output, String model, Integer promptTokens,
                                          Integer completionTokens, List<String> usedTools) {
        return new SkillExecutionResult(true, output, null, model, promptTokens, completionTokens, usedTools);
    }

    public static SkillExecutionResult fail(String error) {
        return new SkillExecutionResult(false, null, error, null, null, null, List.of());
    }
}


