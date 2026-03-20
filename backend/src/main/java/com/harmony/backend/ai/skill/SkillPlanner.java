package com.harmony.backend.ai.skill;

import java.util.List;

public interface SkillPlanner {
    SkillPlan plan(String userPrompt, String requestedSkill, String rawInput, List<String> allowedSkills);
}
