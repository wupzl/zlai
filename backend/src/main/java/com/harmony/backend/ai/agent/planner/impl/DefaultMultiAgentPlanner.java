package com.harmony.backend.ai.agent.planner.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.agent.planner.MultiAgentPlan;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanStep;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanner;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultMultiAgentPlanner implements MultiAgentPlanner {

    private final ObjectMapper objectMapper;

    @Value("${app.agents.workflow-planner-model:}")
    private String plannerModelOverride;

    @Value("${app.agents.workflow-planner-llm-enabled:true}")
    private boolean llmPlannerEnabled;

    @Value("${app.agents.workflow-planner-rule-auto-gap:4}")
    private int ruleAutoGap;

    @Value("${app.agents.workflow-planner-rule-auto-score:10}")
    private int ruleAutoScore;

    @Value("${app.agents.workflow-planner-candidate-limit:3}")
    private int plannerCandidateLimit;

    @Override
    public MultiAgentPlan plan(List<LlmMessage> contextMessages,
                               String defaultModel,
                               Agent manager,
                               List<TeamAgentRuntime> teamAgents,
                               LlmAdapterRegistry adapterRegistry) {
        String userPrompt = extractLastUserPrompt(contextMessages);
        RankedAgents ranked = rankAgents(userPrompt, teamAgents);
        MultiAgentPlan rulePlan = buildRulePlan(userPrompt, ranked);
        if (!shouldUseLlmPlanner(userPrompt, ranked) || !llmPlannerEnabled) {
            rulePlan.deriveStepsIfMissing();
            return rulePlan;
        }
        try {
            String plannerModel = resolvePlannerModel(defaultModel);
            LlmAdapter plannerAdapter = adapterRegistry.getAdapter(plannerModel);
            List<LlmMessage> plannerMessages = new ArrayList<>();
            plannerMessages.add(new LlmMessage("system", """
                    You are a system planner for multi-agent workflow routing.
                    You are not the manager persona and must ignore any manager style instructions.
                    Output JSON only: {"selectedAgentIds":["..."],"parallel":true,"reason":"...","steps":[{"order":1,"agentId":"...","stepType":"SPECIALIST","dependsOnPriorSteps":false,"objective":"..."}]}.
                    Rules:
                    - Select only the minimum useful agents from the provided candidates.
                    - Select at most 3 agents.
                    - Prefer exactly 1 agent if that is sufficient.
                    - Use parallel=false when later specialists should see prior specialist findings.
                    - Step order must match selected agents.
                    - If uncertain, choose the smaller set.
                    """.trim()));
            plannerMessages.add(new LlmMessage("user",
                    "User task:\n" + safe(userPrompt) + "\n\nCandidate agents only:\n" + buildAgentCatalog(ranked.topCandidates())));
            String raw = safe(plannerAdapter.chat(plannerMessages, plannerModel));
            MultiAgentPlan parsed = parsePlan(raw, ranked.topCandidates());
            if (parsed != null && parsed.getSelectedAgentIds() != null && !parsed.getSelectedAgentIds().isEmpty()) {
                parsed.setUsedLlmPlanner(true);
                parsed.setConfidence(Math.max(parsed.getConfidence(), 0.7));
                parsed.deriveStepsIfMissing();
                return parsed;
            }
        } catch (Exception e) {
            log.warn("Planner fallback to rule plan: {}", e.getMessage());
        }
        rulePlan.deriveStepsIfMissing();
        return rulePlan;
    }

    private boolean shouldUseLlmPlanner(String userPrompt, RankedAgents ranked) {
        if (ranked == null || ranked.scored().isEmpty()) {
            return false;
        }
        if (!hasComplexTaskCue(userPrompt) && ranked.scored().size() == 1) {
            return false;
        }
        ScoredRuntime top = ranked.scored().get(0);
        ScoredRuntime second = ranked.scored().size() > 1 ? ranked.scored().get(1) : null;
        int topScore = top.score();
        int secondScore = second != null ? second.score() : 0;
        int gap = topScore - secondScore;
        if (topScore >= ruleAutoScore && gap >= ruleAutoGap) {
            return false;
        }
        return ranked.topCandidates().size() > 1;
    }

    private MultiAgentPlan buildRulePlan(String userPrompt, RankedAgents ranked) {
        MultiAgentPlan plan = new MultiAgentPlan();
        plan.setUsedLlmPlanner(false);
        plan.setConfidence(confidenceFromRanking(ranked));
        if (ranked == null || ranked.scored().isEmpty()) {
            plan.setSelectedAgentIds(List.of());
            plan.setParallel(true);
            plan.setReason("no ranked agents available");
            plan.setSteps(List.of());
            return plan;
        }
        List<TeamAgentRuntime> topCandidates = ranked.topCandidates();
        List<String> selected = new ArrayList<>();
        ScoredRuntime top = ranked.scored().get(0);
        ScoredRuntime second = ranked.scored().size() > 1 ? ranked.scored().get(1) : null;
        int gap = top.score() - (second != null ? second.score() : 0);
        boolean complex = hasComplexTaskCue(userPrompt);
        int target = (!complex && gap >= ruleAutoGap) ? 1 : Math.min(2, topCandidates.size());
        for (int i = 0; i < target; i++) {
            TeamAgentRuntime runtime = topCandidates.get(i);
            if (runtime != null && runtime.getAgent() != null) {
                selected.add(runtime.getAgent().getAgentId());
            }
        }
        if (selected.isEmpty() && !topCandidates.isEmpty() && topCandidates.get(0).getAgent() != null) {
            selected.add(topCandidates.get(0).getAgent().getAgentId());
        }
        plan.setSelectedAgentIds(selected);
        plan.setParallel(complex && selected.size() > 1 && !hasDependencyCue(userPrompt));
        plan.setReason(!complex && gap >= ruleAutoGap ? "rule-selected single dominant specialist" : "rule-selected top candidate set");
        plan.setSteps(buildRuleSteps(plan));
        return plan;
    }

    private List<MultiAgentPlanStep> buildRuleSteps(MultiAgentPlan plan) {
        List<String> ids = plan.getSelectedAgentIds() == null ? List.of() : plan.getSelectedAgentIds();
        List<MultiAgentPlanStep> steps = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            steps.add(new MultiAgentPlanStep(i + 1, ids.get(i), "SPECIALIST", !plan.isParallel() && i > 0, plan.getReason()));
        }
        return steps;
    }

    private RankedAgents rankAgents(String userPrompt, List<TeamAgentRuntime> teamAgents) {
        List<ScoredRuntime> scored = new ArrayList<>();
        String prompt = safe(userPrompt).toLowerCase();
        if (teamAgents != null) {
            for (TeamAgentRuntime runtime : teamAgents) {
                if (runtime == null || runtime.getAgent() == null) {
                    continue;
                }
                int score = 0;
                score += matchScore(prompt, runtime.getRole());
                score += matchScore(prompt, runtime.getAgent().getName());
                score += matchScore(prompt, runtime.getAgent().getDescription());
                if (runtime.getSkills() != null) {
                    for (String skill : runtime.getSkills()) {
                        score += matchScore(prompt, skill) * 2;
                    }
                }
                if (runtime.getTools() != null) {
                    for (String tool : runtime.getTools()) {
                        score += matchScore(prompt, tool);
                    }
                }
                if (score == 0 && scored.isEmpty()) {
                    score = 1;
                }
                scored.add(new ScoredRuntime(runtime, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredRuntime::score).reversed());
        int limit = Math.max(1, Math.min(3, plannerCandidateLimit));
        List<TeamAgentRuntime> topCandidates = scored.stream().filter(item -> item.score() > 0).limit(limit).map(ScoredRuntime::runtime).toList();
        return new RankedAgents(scored, topCandidates);
    }

    private boolean hasComplexTaskCue(String userPrompt) {
        String prompt = safe(userPrompt).toLowerCase();
        return prompt.contains("compare") || prompt.contains("analysis") || prompt.contains("research") || prompt.contains("plan")
                || prompt.contains("recommend") || prompt.contains("difference") || prompt.contains("plan")
                || prompt.contains("compare") || prompt.contains("analysis") || prompt.contains("research");
    }

    private boolean hasDependencyCue(String userPrompt) {
        String prompt = safe(userPrompt).toLowerCase();
        return prompt.contains("then") || prompt.contains("based on") || prompt.contains("after")
                || prompt.contains("first") || prompt.contains("next") || prompt.contains("then") || prompt.contains("based");
    }

    private double confidenceFromRanking(RankedAgents ranked) {
        if (ranked == null || ranked.scored().isEmpty()) {
            return 0.2;
        }
        int top = ranked.scored().get(0).score();
        int second = ranked.scored().size() > 1 ? ranked.scored().get(1).score() : 0;
        int gap = Math.max(0, top - second);
        return Math.max(0.2, Math.min(0.9, (top + gap) / 20.0));
    }

    private String resolvePlannerModel(String defaultModel) {
        if (plannerModelOverride != null && !plannerModelOverride.isBlank()) {
            return plannerModelOverride.trim();
        }
        return defaultModel;
    }

    private int matchScore(String prompt, String candidate) {
        if (prompt == null || prompt.isBlank() || candidate == null || candidate.isBlank()) {
            return 0;
        }
        String normalized = candidate.toLowerCase();
        int score = 0;
        for (String token : normalized.split("[\\s,_-]+")) {
            if (token.length() < 3) {
                continue;
            }
            if (prompt.contains(token)) {
                score += 2;
            }
        }
        if (prompt.contains(normalized)) {
            score += 3;
        }
        return score;
    }

    private MultiAgentPlan parsePlan(String raw, List<TeamAgentRuntime> candidateAgents) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            MultiAgentPlan plan = objectMapper.readValue(raw.substring(start, end + 1), MultiAgentPlan.class);
            if (plan.getSelectedAgentIds() == null) {
                return null;
            }
            Set<String> valid = new HashSet<>();
            for (TeamAgentRuntime runtime : candidateAgents) {
                if (runtime != null && runtime.getAgent() != null) {
                    valid.add(runtime.getAgent().getAgentId());
                }
            }
            plan.setSelectedAgentIds(plan.getSelectedAgentIds().stream().filter(valid::contains).limit(3).toList());
            if (plan.getSteps() != null && !plan.getSteps().isEmpty()) {
                plan.setSteps(plan.getSteps().stream().filter(step -> valid.contains(step.getAgentId())).limit(3).toList());
            }
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildAgentCatalog(List<TeamAgentRuntime> teamAgents) {
        StringBuilder sb = new StringBuilder();
        for (TeamAgentRuntime runtime : teamAgents) {
            if (runtime == null || runtime.getAgent() == null) {
                continue;
            }
            Agent agent = runtime.getAgent();
            sb.append("- agentId=").append(agent.getAgentId())
                    .append(", name=").append(safe(agent.getName()))
                    .append(", role=").append(safe(runtime.getRole()))
                    .append(", description=").append(safe(agent.getDescription()))
                    .append(", skills=").append(runtime.getSkills() == null ? "[]" : runtime.getSkills())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String extractLastUserPrompt(List<LlmMessage> contextMessages) {
        if (contextMessages == null) {
            return null;
        }
        for (int i = contextMessages.size() - 1; i >= 0; i--) {
            LlmMessage message = contextMessages.get(i);
            if (message != null && "user".equalsIgnoreCase(message.getRole())) {
                return message.getContent();
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record ScoredRuntime(TeamAgentRuntime runtime, int score) {}

    private record RankedAgents(List<ScoredRuntime> scored, List<TeamAgentRuntime> topCandidates) {}
}

