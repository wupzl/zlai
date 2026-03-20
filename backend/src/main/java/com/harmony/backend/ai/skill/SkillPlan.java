package com.harmony.backend.ai.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillPlan {
    private String selectedSkillKey;
    private List<SkillCandidate> candidates;
    private String normalizedInput;
    private String reason;

    public boolean hasSelection() {
        return selectedSkillKey != null && !selectedSkillKey.isBlank();
    }
}
