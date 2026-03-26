package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.agent.planner.MultiAgentPlan;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentPlanResolver {

    public ResolvedAgentPlan resolve(List<TeamAgentRuntime> teamAgents, MultiAgentPlan plan) {
        List<TeamAgentRuntime> selectedAgents = selectAgents(teamAgents, plan);
        List<MultiAgentPlanStep> steps = plan != null ? plan.getSteps() : List.of();
        if ((steps == null || steps.isEmpty()) && plan != null) {
            plan.deriveStepsIfMissing();
            steps = plan.getSteps();
        }
        return new ResolvedAgentPlan(selectedAgents, steps == null ? List.of() : steps, plan != null && plan.isParallel(), plan != null ? plan.getReason() : null);
    }

    private List<TeamAgentRuntime> selectAgents(List<TeamAgentRuntime> teamAgents, MultiAgentPlan plan) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            return List.of();
        }
        if (plan == null || plan.getSelectedAgentIds() == null || plan.getSelectedAgentIds().isEmpty()) {
            return teamAgents.size() <= 2 ? teamAgents : teamAgents.subList(0, 2);
        }
        List<TeamAgentRuntime> selected = new ArrayList<>();
        for (String selectedId : plan.getSelectedAgentIds()) {
            for (TeamAgentRuntime runtime : teamAgents) {
                if (runtime != null && runtime.getAgent() != null && selectedId.equals(runtime.getAgent().getAgentId())) {
                    selected.add(runtime);
                    break;
                }
            }
        }
        return selected;
    }

    public record ResolvedAgentPlan(List<TeamAgentRuntime> selectedAgents,
                                    List<MultiAgentPlanStep> steps,
                                    boolean parallel,
                                    String reason) {
    }
}
