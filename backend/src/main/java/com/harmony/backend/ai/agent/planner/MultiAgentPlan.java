package com.harmony.backend.ai.agent.planner;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultiAgentPlan {
    private List<String> selectedAgentIds;
    private boolean parallel = true;
    private String reason;
    private boolean usedLlmPlanner;
    private double confidence;
}