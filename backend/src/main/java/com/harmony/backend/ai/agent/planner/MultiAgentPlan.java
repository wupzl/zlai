package com.harmony.backend.ai.agent.planner;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class MultiAgentPlan {
    private List<String> selectedAgentIds;
    private boolean parallel = true;
    private String reason;
    private boolean usedLlmPlanner;
    private double confidence;
    private List<MultiAgentPlanStep> steps = List.of();

    public MultiAgentPlan(List<String> selectedAgentIds,
                          boolean parallel,
                          String reason,
                          boolean usedLlmPlanner,
                          double confidence) {
        this(selectedAgentIds, parallel, reason, usedLlmPlanner, confidence, List.of());
    }

    public MultiAgentPlan(List<String> selectedAgentIds,
                          boolean parallel,
                          String reason,
                          boolean usedLlmPlanner,
                          double confidence,
                          List<MultiAgentPlanStep> steps) {
        this.selectedAgentIds = selectedAgentIds;
        this.parallel = parallel;
        this.reason = reason;
        this.usedLlmPlanner = usedLlmPlanner;
        this.confidence = confidence;
        this.steps = steps == null ? List.of() : steps;
    }

    public void deriveStepsIfMissing() {
        if (steps != null && !steps.isEmpty()) {
            return;
        }
        List<MultiAgentPlanStep> derived = new ArrayList<>();
        List<String> ids = selectedAgentIds == null ? List.of() : selectedAgentIds;
        for (int i = 0; i < ids.size(); i++) {
            derived.add(new MultiAgentPlanStep(
                    i + 1,
                    ids.get(i),
                    "SPECIALIST",
                    !parallel && i > 0,
                    reason
            ));
        }
        this.steps = derived;
    }
}
