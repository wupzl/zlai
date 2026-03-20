package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillInputField {
    private String key;
    private String type;
    private Boolean required;
    private String description;
}
