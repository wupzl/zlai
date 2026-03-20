package com.harmony.backend.ai.skill.model;

import com.harmony.backend.ai.skill.SkillInputField;
import com.harmony.backend.ai.skill.SkillStepDefinition;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SkillVO {
    private String key;
    private String name;
    private String description;
    private List<String> toolKeys;
    private String executionMode;
    private List<SkillInputField> inputSchema;
    private List<SkillStepDefinition> stepConfig;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
