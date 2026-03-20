package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AgentSkillDefinition {
    private String key;
    private String name;
    private String description;
    private List<String> toolKeys;
    private String executionMode;
    private List<SkillInputField> inputSchema;
    private List<SkillStepDefinition> stepConfig;
}
