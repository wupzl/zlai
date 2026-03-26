package com.harmony.backend.ai.agent.planner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentPlanStep {
    private int order;
    private String agentId;
    private String stepType;
    private boolean dependsOnPriorSteps;
    private String objective;
}
