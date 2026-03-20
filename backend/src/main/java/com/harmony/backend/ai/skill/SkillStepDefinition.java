package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillStepDefinition {
    private String toolKey;
    private String prompt;
}
