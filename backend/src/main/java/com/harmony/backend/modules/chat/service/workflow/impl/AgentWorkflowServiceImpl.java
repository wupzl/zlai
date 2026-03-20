package com.harmony.backend.modules.chat.service.workflow.impl;

import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.prompt.ChatPromptService;
import com.harmony.backend.modules.chat.service.BillingService;
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import com.harmony.backend.modules.chat.service.workflow.AgentWorkflowService;
import com.harmony.backend.modules.chat.service.workflow.model.WorkflowExecutionResult;
import com.harmony.backend.modules.chat.service.workflow.planner.SingleAgentPlan;
import com.harmony.backend.modules.chat.service.workflow.planner.SingleAgentPlanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AgentWorkflowServiceImpl implements AgentWorkflowService {

    private static final String WORKFLOW_DIRECT_QA = "direct_qa_workflow";

    private final SingleAgentPlanner singleAgentPlanner;
    private final SkillExecutor skillExecutor;
    private final LlmAdapterRegistry adapterRegistry;
    private final ChatPromptService chatPromptService;
    private final BillingService billingService;
    private final AgentMemoryService agentMemoryService;

    @Value("${app.workflow.sync-enabled:true}")
    private boolean syncWorkflowEnabled;

    @Value("${app.workflow.stream-enabled:true}")
    private boolean streamWorkflowEnabled;

    @Override
    public WorkflowExecutionResult executeSync(Long userId,
                                               Session session,
                                               Agent agent,
                                               List<LlmMessage> messages,
                                               String model,
                                               String assistantMessageId) {
        return executeInternal(userId, session, agent, messages, model, assistantMessageId, false);
    }

    @Override
    public WorkflowExecutionResult executeStream(Long userId,
                                                 Session session,
                                                 Agent agent,
                                                 List<LlmMessage> messages,
                                                 String model,
                                                 String assistantMessageId) {
        return executeInternal(userId, session, agent, messages, model, assistantMessageId, true);
    }

    private WorkflowExecutionResult executeInternal(Long userId,
                                                    Session session,
                                                    Agent agent,
                                                    List<LlmMessage> messages,
                                                    String model,
                                                    String assistantMessageId,
                                                    boolean streamMode) {
        if ((!streamMode && !syncWorkflowEnabled) || (streamMode && !streamWorkflowEnabled)) {
            return WorkflowExecutionResult.notHandled();
        }
        if (session == null || agent == null || messages == null || messages.isEmpty()) {
            return WorkflowExecutionResult.notHandled();
        }
        String userPrompt = extractLastUserPrompt(messages);
        if (!StringUtils.hasText(userPrompt)) {
            return WorkflowExecutionResult.notHandled();
        }

        SingleAgentPlan plan = singleAgentPlanner.plan(session, agent, messages, model, adapterRegistry);
        if (plan == null) {
            return WorkflowExecutionResult.notHandled();
        }
        if (streamMode && !plan.isUseSkill()) {
            return WorkflowExecutionResult.notHandled();
        }

        recordWorkflowProgress(session, userId, assistantMessageId, plan, "planned", userPrompt,
                planArtifacts(plan), "ACTIVE");

        if (!plan.isUseSkill() || !StringUtils.hasText(plan.getSelectedSkillKey())) {
            return WorkflowExecutionResult.handled(plan.getWorkflowKey(), runDirectAnswer(messages, model));
        }

        String skillModel = resolveToolModel(session, agent);
        SkillExecutionResult result = skillExecutor.execute(new SkillExecutionRequest(
                plan.getSelectedSkillKey(),
                plan.getNormalizedInput(),
                skillModel
        ));
        if (result == null || !result.isSuccess()) {
            String error = result != null ? result.getError() : "unknown error";
            log.warn("Workflow skill failed: workflowKey={}, skillKey={}, error={}", plan.getWorkflowKey(), plan.getSelectedSkillKey(), error);
            recordWorkflowProgress(session, userId, assistantMessageId, plan, "skill_failed", userPrompt,
                    failureArtifacts(plan, error), "FAILED");
            if (streamMode) {
                return WorkflowExecutionResult.notHandled();
            }
            return WorkflowExecutionResult.handled(plan.getWorkflowKey(), runDirectAnswer(messages, model));
        }

        billingService.recordToolConsumption(session, assistantMessageId, result.getModel(),
                result.getPromptTokens(), result.getCompletionTokens());
        agentMemoryService.recordSkillExecution(userId, session.getChatId(), plan.getSelectedSkillKey(),
                plan.getNormalizedInput(), result.getOutput(), assistantMessageId);
        recordWorkflowProgress(session, userId, assistantMessageId, plan, "skill_executed", userPrompt,
                successArtifacts(plan, result), "ACTIVE");

        String finalAnswer = composeFinalAnswer(messages, model, result.getOutput());
        recordWorkflowProgress(session, userId, assistantMessageId, plan, "answered", userPrompt,
                answerArtifacts(plan, result, finalAnswer), "ACTIVE");
        return WorkflowExecutionResult.handled(plan.getWorkflowKey(), finalAnswer);
    }

    private String runDirectAnswer(List<LlmMessage> messages, String model) {
        LlmAdapter adapter = adapterRegistry.getAdapter(model);
        String response = adapter.chat(messages, model);
        return response == null ? "" : response;
    }

    private String composeFinalAnswer(List<LlmMessage> messages, String model, String skillOutput) {
        boolean useChinese = preferChinese(extractLastUserPrompt(messages));
        List<LlmMessage> followup = new ArrayList<>(messages);
        followup.add(new LlmMessage("assistant", "Skill execution completed."));
        followup.add(new LlmMessage("user", chatPromptService.buildToolFollowupUserMessage(skillOutput, useChinese, null)));
        LlmAdapter adapter = adapterRegistry.getAdapter(model);
        String answer = adapter.chat(followup, model);
        if (!StringUtils.hasText(answer)) {
            return skillOutput == null ? "" : skillOutput;
        }
        return answer;
    }

    private String resolveToolModel(Session session, Agent agent) {
        if (session != null && StringUtils.hasText(session.getToolModel())) {
            return session.getToolModel().trim();
        }
        if (agent != null && StringUtils.hasText(agent.getToolModel())) {
            return agent.getToolModel().trim();
        }
        return null;
    }

    private void recordWorkflowProgress(Session session,
                                        Long userId,
                                        String assistantMessageId,
                                        SingleAgentPlan plan,
                                        String currentStep,
                                        String goal,
                                        String artifactsJson,
                                        String status) {
        if (session == null || plan == null) {
            return;
        }
        agentMemoryService.recordWorkflowProgress(userId,
                session.getChatId(),
                plan.getWorkflowKey(),
                goal,
                currentStep,
                status,
                artifactsJson,
                assistantMessageId);
    }

    private String planArtifacts(SingleAgentPlan plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "planned");
        payload.put("workflow_key", plan.getWorkflowKey());
        payload.put("selected_skill", plan.getSelectedSkillKey());
        payload.put("confidence", plan.getConfidence());
        payload.put("reason", plan.getReason());
        payload.put("used_llm_planner", plan.isUsedLlmPlanner());
        return json(payload);
    }

    private String failureArtifacts(SingleAgentPlan plan, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "skill_failed");
        payload.put("workflow_key", plan.getWorkflowKey());
        payload.put("selected_skill", plan.getSelectedSkillKey());
        payload.put("error", error);
        return json(payload);
    }

    private String successArtifacts(SingleAgentPlan plan, SkillExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "skill_executed");
        payload.put("workflow_key", plan.getWorkflowKey());
        payload.put("selected_skill", plan.getSelectedSkillKey());
        payload.put("normalized_input", plan.getNormalizedInput());
        payload.put("used_tools", result != null ? result.getUsedTools() : null);
        payload.put("skill_output_excerpt", compact(result != null ? result.getOutput() : null, 600));
        return json(payload);
    }

    private String answerArtifacts(SingleAgentPlan plan, SkillExecutionResult result, String finalAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "answered");
        payload.put("workflow_key", plan.getWorkflowKey());
        payload.put("selected_skill", plan.getSelectedSkillKey());
        payload.put("used_tools", result != null ? result.getUsedTools() : null);
        payload.put("final_answer_excerpt", compact(finalAnswer, 600));
        return json(payload);
    }

    private String json(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private String compact(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, max));
    }

    private boolean preferChinese(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        String lower = text.toLowerCase();
        return lower.contains("chinese") || lower.contains("zh");
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
}