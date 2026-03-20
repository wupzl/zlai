package com.harmony.backend.modules.chat.service.workflow.planner.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.SkillCandidate;
import com.harmony.backend.ai.skill.SkillPlan;
import com.harmony.backend.ai.skill.SkillPlanner;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.service.support.ChatSessionSupportService;
import com.harmony.backend.modules.chat.service.workflow.planner.SingleAgentPlan;
import com.harmony.backend.modules.chat.service.workflow.planner.SingleAgentPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultSingleAgentPlanner implements SingleAgentPlanner {

    private static final String WORKFLOW_DIRECT_QA = "direct_qa_workflow";
    private static final String WORKFLOW_SKILL = "skill_workflow";
    private static final String WORKFLOW_RESEARCH = "research_workflow";

    private final ChatSessionSupportService chatSessionSupportService;
    private final SkillPlanner skillPlanner;
    private final ObjectMapper objectMapper;

    @Value("${app.workflow.skill-score-threshold:10}")
    private int skillScoreThreshold;

    @Value("${app.workflow.single-planner-rule-gap-threshold:8}")
    private int ruleGapThreshold;

    @Value("${app.workflow.single-planner-rule-auto-threshold:14}")
    private int ruleAutoThreshold;

    @Value("${app.workflow.single-planner-llm-enabled:true}")
    private boolean llmPlannerEnabled;

    @Value("${app.workflow.single-planner-model:}")
    private String plannerModelOverride;

    @Override
    public SingleAgentPlan plan(Session session,
                                Agent agent,
                                List<LlmMessage> messages,
                                String model,
                                LlmAdapterRegistry adapterRegistry) {
        if (agent == null || messages == null || messages.isEmpty()) {
            return directPlan("missing agent or messages", 0.0, false);
        }
        List<String> allowedSkills = chatSessionSupportService.readAgentSkills(agent);
        if (allowedSkills == null || allowedSkills.isEmpty()) {
            return directPlan("no allowed skills", 0.0, false);
        }
        String userPrompt = extractLastUserPrompt(messages);
        if (!StringUtils.hasText(userPrompt)) {
            return directPlan("missing user prompt", 0.0, false);
        }

        SkillPlan skillPlan = skillPlanner.plan(userPrompt, null, userPrompt, allowedSkills);
        SkillCandidate top = candidateAt(skillPlan, 0);
        SkillCandidate second = candidateAt(skillPlan, 1);
        int topScore = top != null ? top.getScore() : 0;
        int secondScore = second != null ? second.getScore() : 0;
        int gap = topScore - secondScore;

        if (top == null || !skillPlan.hasSelection() || topScore < Math.max(1, skillScoreThreshold)) {
            return directPlan("top skill score below threshold", confidenceFromScore(topScore), false);
        }

        if (matchesStrongRule(userPrompt, skillPlan.getSelectedSkillKey())
                || (topScore >= ruleAutoThreshold && gap >= Math.max(1, ruleGapThreshold))) {
            return skillPlanToWorkflow(skillPlan,
                    routeWorkflowKey(skillPlan.getSelectedSkillKey()),
                    confidenceFromScore(topScore),
                    matchesStrongRule(userPrompt, skillPlan.getSelectedSkillKey())
                            ? "rule matched obvious intent"
                            : "rule matched clear score gap",
                    false);
        }

        if (llmPlannerEnabled) {
            SingleAgentPlan llmPlan = planWithLlm(userPrompt, model, adapterRegistry, skillPlan);
            if (llmPlan != null) {
                return llmPlan;
            }
        }

        return skillPlanToWorkflow(skillPlan,
                routeWorkflowKey(skillPlan.getSelectedSkillKey()),
                confidenceFromScore(topScore),
                "fallback to highest-scoring allowed skill",
                false);
    }

    private SingleAgentPlan planWithLlm(String userPrompt,
                                        String defaultModel,
                                        LlmAdapterRegistry adapterRegistry,
                                        SkillPlan skillPlan) {
        try {
            String plannerModel = StringUtils.hasText(plannerModelOverride) ? plannerModelOverride.trim() : defaultModel;
            LlmAdapter adapter = adapterRegistry.getAdapter(plannerModel);
            List<LlmMessage> plannerMessages = new ArrayList<>();
            plannerMessages.add(new LlmMessage("system", """
                    You are a single-agent workflow planner.
                    Choose between direct_qa_workflow, skill_workflow, and research_workflow.
                    You may choose a skill only from the provided candidates.
                    Output JSON only: {"workflowKey":"...","selectedSkillKey":"...","useSkill":true,"reason":"..."}.
                    Rules:
                    - Use direct_qa_workflow when the user can be answered directly without tools.
                    - Use research_workflow only for web_research-like tasks.
                    - Use skill_workflow for other clear tool-backed tasks.
                    - If uncertain, prefer direct_qa_workflow.
                    """.trim()));
            plannerMessages.add(new LlmMessage("user",
                    "User task:\n" + userPrompt + "\n\nCandidates:\n" + buildCandidateCatalog(skillPlan)));
            String raw = adapter.chat(plannerMessages, plannerModel);
            return parseLlmPlan(raw, skillPlan);
        } catch (Exception e) {
            log.warn("Single-agent LLM planner fallback to rules: {}", e.getMessage());
            return null;
        }
    }

    private SingleAgentPlan parseLlmPlan(String raw, SkillPlan skillPlan) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(raw.substring(start, end + 1), Map.class);
            String workflowKey = stringValue(payload.get("workflowKey"));
            String selectedSkillKey = stringValue(payload.get("selectedSkillKey"));
            boolean useSkill = Boolean.parseBoolean(String.valueOf(payload.getOrDefault("useSkill", false)));
            String reason = stringValue(payload.get("reason"));
            if (!useSkill || !StringUtils.hasText(selectedSkillKey)) {
                return directPlan(StringUtils.hasText(reason) ? reason : "llm selected direct answer", 0.55, true);
            }
            if (!candidateMatches(skillPlan, selectedSkillKey)) {
                return null;
            }
            String normalizedInput = normalizeForSelectedSkill(skillPlan, selectedSkillKey);
            String resolvedWorkflow = StringUtils.hasText(workflowKey) ? workflowKey.trim() : routeWorkflowKey(selectedSkillKey);
            if (!WORKFLOW_SKILL.equals(resolvedWorkflow) && !WORKFLOW_RESEARCH.equals(resolvedWorkflow)) {
                resolvedWorkflow = routeWorkflowKey(selectedSkillKey);
            }
            return new SingleAgentPlan(resolvedWorkflow, selectedSkillKey, normalizedInput, true, 0.65,
                    StringUtils.hasText(reason) ? reason : "llm selected candidate workflow", true);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean candidateMatches(SkillPlan skillPlan, String selectedSkillKey) {
        if (skillPlan == null || skillPlan.getCandidates() == null) {
            return false;
        }
        for (SkillCandidate candidate : skillPlan.getCandidates()) {
            if (candidate != null && selectedSkillKey.equalsIgnoreCase(candidate.getSkillKey())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForSelectedSkill(SkillPlan skillPlan, String selectedSkillKey) {
        if (skillPlan == null) {
            return null;
        }
        if (selectedSkillKey != null && selectedSkillKey.equalsIgnoreCase(skillPlan.getSelectedSkillKey())) {
            return skillPlan.getNormalizedInput();
        }
        return skillPlan.getNormalizedInput();
    }

    private boolean matchesStrongRule(String userPrompt, String skillKey) {
        if (!StringUtils.hasText(userPrompt) || !StringUtils.hasText(skillKey)) {
            return false;
        }
        String lower = userPrompt.toLowerCase();
        return switch (skillKey.toLowerCase()) {
            case "calculation" -> looksLikeCalculation(lower) || lower.contains("calculate") || lower.contains("math");
            case "translation" -> lower.contains("translate") || lower.contains("translation");
            case "time_lookup" -> lower.contains("time") || lower.contains("timezone") || lower.contains("date");
            case "summarization" -> lower.contains("summarize") || lower.contains("summary") || lower.contains("tl;dr");
            case "web_research" -> lower.contains("research") || lower.contains("search") || lower.contains("look up") || lower.contains("find information");
            default -> false;
        };
    }

    private boolean looksLikeCalculation(String lower) {
        if (!StringUtils.hasText(lower)) {
            return false;
        }
        boolean hasDigit = false;
        boolean hasOperator = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isDigit(ch)) {
                hasDigit = true;
            }
            if (ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '(' || ch == ')' || ch == '=') {
                hasOperator = true;
            }
        }
        return hasDigit && hasOperator;
    }
    private String buildCandidateCatalog(SkillPlan skillPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("- workflow: direct_qa_workflow\n");
        if (skillPlan == null || skillPlan.getCandidates() == null) {
            return sb.toString().trim();
        }
        for (SkillCandidate candidate : skillPlan.getCandidates().stream().limit(3).toList()) {
            if (candidate == null) {
                continue;
            }
            String workflow = routeWorkflowKey(candidate.getSkillKey());
            sb.append("- workflow: ").append(workflow)
                    .append(", skill: ").append(candidate.getSkillKey())
                    .append(", score: ").append(candidate.getScore())
                    .append(", reason: ").append(candidate.getReason())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private SkillCandidate candidateAt(SkillPlan skillPlan, int index) {
        if (skillPlan == null || skillPlan.getCandidates() == null || skillPlan.getCandidates().size() <= index) {
            return null;
        }
        return skillPlan.getCandidates().get(index);
    }

    private SingleAgentPlan skillPlanToWorkflow(SkillPlan skillPlan,
                                                String workflowKey,
                                                double confidence,
                                                String reason,
                                                boolean usedLlmPlanner) {
        return new SingleAgentPlan(workflowKey,
                skillPlan != null ? skillPlan.getSelectedSkillKey() : null,
                skillPlan != null ? skillPlan.getNormalizedInput() : null,
                true,
                confidence,
                reason,
                usedLlmPlanner);
    }

    private SingleAgentPlan directPlan(String reason, double confidence, boolean usedLlmPlanner) {
        return new SingleAgentPlan(WORKFLOW_DIRECT_QA, null, null, false, confidence, reason, usedLlmPlanner);
    }

    private String routeWorkflowKey(String skillKey) {
        if ("web_research".equalsIgnoreCase(skillKey)) {
            return WORKFLOW_RESEARCH;
        }
        return WORKFLOW_SKILL;
    }

    private double confidenceFromScore(int score) {
        return Math.max(0.05, Math.min(0.95, score / 25.0));
    }

    private String extractLastUserPrompt(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message != null && "user".equalsIgnoreCase(message.getRole())) {
                return message.getContent();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }
}