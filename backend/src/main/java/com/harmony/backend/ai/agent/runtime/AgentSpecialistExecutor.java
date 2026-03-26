package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.ai.skill.SkillPlan;
import com.harmony.backend.ai.skill.SkillPlanner;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class AgentSpecialistExecutor {

    private final SkillExecutor skillExecutor;
    private final SkillPlanner skillPlanner;
    private final AgentSkillRegistry skillRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AgentSpecialistExecutor(SkillExecutor skillExecutor,
                                   SkillPlanner skillPlanner,
                                   AgentSkillRegistry skillRegistry,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.skillExecutor = skillExecutor;
        this.skillPlanner = skillPlanner;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentStepExecutionResult execute(TeamAgentRuntime runtime,
                                            List<LlmMessage> contextMessages,
                                            String defaultModel,
                                            LlmAdapterRegistry adapterRegistry,
                                            MultiAgentOrchestrator.ToolUsageRecorder usageRecorder,
                                            String executionId,
                                            String stepKey) {
        if (runtime == null || runtime.getAgent() == null) {
            return new AgentStepExecutionResult("", 0);
        }
        Agent agent = runtime.getAgent();
        String model = agent.getModel() == null || agent.getModel().isBlank() ? defaultModel : agent.getModel();
        LlmAdapter adapter = adapterRegistry.getAdapter(model);
        String draft = safe(adapter.chat(buildTeamMessages(runtime, contextMessages), model));
        SkillInvocationOutcome skillOutcome = trySkillCall(draft, runtime, adapter, model, contextMessages, usageRecorder, executionId, stepKey);
        if (skillOutcome != null) {
            return new AgentStepExecutionResult(skillOutcome.output(), skillOutcome.toolCalls());
        }
        return new AgentStepExecutionResult(draft, 0);
    }

    public List<LlmMessage> buildTeamMessages(TeamAgentRuntime runtime, List<LlmMessage> contextMessages) {
        Agent agent = runtime.getAgent();
        List<LlmMessage> messages = new ArrayList<>();
        String system = agent.getInstructions();
        if (system == null || system.isBlank()) {
            system = "You are Specialist Agent. Provide a concise expert answer.";
        }
        if (runtime.getRole() != null && !runtime.getRole().isBlank()) {
            system = "Role: " + runtime.getRole().trim() + "\n" + system;
        }
        if (runtime.getSkills() != null && !runtime.getSkills().isEmpty()) {
            system = system + "\n\nAllowed skills:";
            for (String skillKey : runtime.getSkills()) {
                var skill = skillRegistry.get(skillKey);
                if (skill != null) {
                    system += "\n- " + skill.getKey() + ": " + skill.getDescription();
                }
            }
            system += "\nIf you need a skill, respond ONLY with JSON: {\"skill\":\"<key>\",\"input\":\"...\"}.";
        }
        messages.add(new LlmMessage("system", system));
        messages.addAll(contextMessages);
        return messages;
    }

    private SkillInvocationOutcome trySkillCall(String draft,
                                                TeamAgentRuntime runtime,
                                                LlmAdapter adapter,
                                                String model,
                                                List<LlmMessage> contextMessages,
                                                MultiAgentOrchestrator.ToolUsageRecorder usageRecorder,
                                                String executionId,
                                                String stepKey) {
        if (draft == null || runtime == null) {
            return null;
        }
        String trimmed = draft.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            String requestedSkill = toStringValue(map.get("skill"));
            if (requestedSkill == null) {
                String toolKey = toStringValue(map.get("tool"));
                requestedSkill = skillRegistry.findSkillForTool(toolKey, runtime.getSkills());
            }
            String input = toStringValue(map.get("input"));
            if (input == null || input.isBlank()) {
                input = extractLastUserPrompt(contextMessages);
            }
            SkillPlan plan = skillPlanner.plan(extractLastUserPrompt(contextMessages), requestedSkill, input, runtime.getSkills());
            if (plan == null || !plan.hasSelection()) {
                return null;
            }
            String output = runSkillFollowup(plan.getSelectedSkillKey(), plan.getNormalizedInput(), runtime, adapter, model, contextMessages, usageRecorder, draft, executionId, stepKey);
            return new SkillInvocationOutcome(output, 1);
        } catch (Exception e) {
            return null;
        }
    }

    private String runSkillFollowup(String skillKey,
                                    String input,
                                    TeamAgentRuntime runtime,
                                    LlmAdapter adapter,
                                    String model,
                                    List<LlmMessage> contextMessages,
                                    MultiAgentOrchestrator.ToolUsageRecorder usageRecorder,
                                    String draft,
                                    String executionId,
                                    String stepKey) {
        String skillModel = runtime.getAgent() != null ? runtime.getAgent().getToolModel() : null;
        String finalInput = normalizeSkillInput(skillKey, input, extractLastUserPrompt(contextMessages));
        SkillExecutionResult result = skillExecutor.execute(new SkillExecutionRequest(skillKey, finalInput, skillModel, executionId, stepKey));
        if (result == null || !result.isSuccess()) {
            return "Skill execution failed: " + (result != null ? result.getError() : "unknown error");
        }
        if (usageRecorder != null && result.getPromptTokens() != null && result.getCompletionTokens() != null) {
            usageRecorder.record(result.getModel(), result.getPromptTokens(), result.getCompletionTokens());
        }
        List<LlmMessage> followup = new ArrayList<>(contextMessages);
        if (draft != null && !draft.isBlank()) {
            followup.add(new LlmMessage("assistant", draft));
        }
        followup.add(new LlmMessage("system", "You must use skill results as the single source of truth. Do NOT invent data."));
        followup.add(new LlmMessage("user", "Skill result:\n" + safe(result.getOutput()) + "\n\nProvide the final answer based only on this result."));
        return safe(adapter.chat(followup, model));
    }

    private String normalizeSkillInput(String skillKey, String input, String userPrompt) {
        String safeInput = input == null ? "" : input.trim();
        if ("time_lookup".equalsIgnoreCase(skillKey)) {
            if (safeInput.isBlank()) {
                if (userPrompt != null && (userPrompt.contains("上海") || userPrompt.contains("北京") || userPrompt.contains("中国"))) {
                    return "Asia/Shanghai";
                }
                return "UTC";
            }
            return safeInput;
        }
        if ("web_research".equalsIgnoreCase(skillKey)) {
            String query = userPrompt == null ? safeInput : userPrompt;
            return query.replaceAll("\\b20\\d{2}\\b", String.valueOf(LocalDate.now().getYear())).trim();
        }
        return safeInput.isBlank() ? safe(userPrompt) : safeInput;
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

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record SkillInvocationOutcome(String output, int toolCalls) {
    }
}
