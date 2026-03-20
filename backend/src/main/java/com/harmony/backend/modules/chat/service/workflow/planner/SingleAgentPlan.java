package com.harmony.backend.modules.chat.service.workflow.planner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SingleAgentPlan {
    private String workflowKey;
    private String selectedSkillKey;
    private String normalizedInput;
    private boolean useSkill;
    private double confidence;
    private String reason;
    private boolean usedLlmPlanner;
}