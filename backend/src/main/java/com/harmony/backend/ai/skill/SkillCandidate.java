package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillCandidate {
    private String skillKey;
    private int score;
    private String reason;
}
