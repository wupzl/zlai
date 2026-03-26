package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillExecutionRequest {
    private String skillKey;
    private String input;
    private String modelOverride;
    private String executionId;
    private String stepKey;

    public SkillExecutionRequest(String skillKey, String input, String modelOverride) {
        this(skillKey, input, modelOverride, null, null);
    }
}
