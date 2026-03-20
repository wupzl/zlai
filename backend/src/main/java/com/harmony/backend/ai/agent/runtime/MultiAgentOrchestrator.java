package com.harmony.backend.ai.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.agent.planner.MultiAgentPlan;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanner;
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
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class MultiAgentOrchestrator {

    private static final String TEAM_WORKFLOW_KEY = "multi_agent_team_workflow";

    private final Executor agentExecutor;
    private final long agentTimeoutSeconds;
    private final SkillExecutor skillExecutor;
    private final SkillPlanner skillPlanner;
    private final AgentSkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;
    private final MultiAgentPlanner multiAgentPlanner;

    public MultiAgentOrchestrator(@Qualifier("agentExecutor") Executor agentExecutor,
                                  @Value("${app.agents.multiagent-timeout-seconds:20}") long agentTimeoutSeconds,
                                  SkillExecutor skillExecutor,
                                  SkillPlanner skillPlanner,
                                  AgentSkillRegistry skillRegistry,
                                  ObjectMapper objectMapper,
                                  AgentMemoryService agentMemoryService,
                                  MultiAgentPlanner multiAgentPlanner) {
        this.agentExecutor = agentExecutor;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
        this.skillExecutor = skillExecutor;
        this.skillPlanner = skillPlanner;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
        this.agentMemoryService = agentMemoryService;
        this.multiAgentPlanner = multiAgentPlanner;
    }

    public String run(List<LlmMessage> contextMessages, String model, LlmAdapter adapter) {
        return safe(adapter.chat(contextMessages, model));
    }

    public Flux<String> stream(List<LlmMessage> contextMessages, String model, LlmAdapter adapter) {
        return adapter.streamChat(contextMessages, model);
    }

    public String runTeam(List<LlmMessage> contextMessages,
                          String defaultModel,
                          Agent manager,
                          List<TeamAgentRuntime> teamAgents,
                          LlmAdapterRegistry adapterRegistry,
                          ToolUsageRecorder usageRecorder,
                          Long userId,
                          String chatId,
                          String assistantMessageId) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            LlmAdapter adapter = adapterRegistry.getAdapter(defaultModel);
            return run(contextMessages, defaultModel, adapter);
        }
        TeamWorkflowResult workflow = executeTeamWorkflow(contextMessages, defaultModel, manager, teamAgents,
                adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return workflow.finalAnswer();
    }

    public Flux<String> streamTeam(List<LlmMessage> contextMessages,
                                   String defaultModel,
                                   Agent manager,
                                   List<TeamAgentRuntime> teamAgents,
                                   LlmAdapterRegistry adapterRegistry,
                                   ToolUsageRecorder usageRecorder,
                                   Long userId,
                                   String chatId,
                                   String assistantMessageId) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            LlmAdapter adapter = adapterRegistry.getAdapter(defaultModel);
            return stream(contextMessages, defaultModel, adapter);
        }
        TeamWorkflowResult workflow = executeTeamWorkflow(contextMessages, defaultModel, manager, teamAgents,
                adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return Flux.create(sink -> {
            emitChunked(sink, workflow.finalAnswer());
            sink.complete();
        });
    }

    private TeamWorkflowResult executeTeamWorkflow(List<LlmMessage> contextMessages,
                                                   String defaultModel,
                                                   Agent manager,
                                                   List<TeamAgentRuntime> teamAgents,
                                                   LlmAdapterRegistry adapterRegistry,
                                                   ToolUsageRecorder usageRecorder,
                                                   Long userId,
                                                   String chatId,
                                                   String assistantMessageId) {
        MultiAgentPlan plan = multiAgentPlanner.plan(contextMessages, defaultModel, manager, teamAgents, adapterRegistry);
        List<TeamAgentRuntime> selectedAgents = selectAgents(teamAgents, plan);
        recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "planned",
                buildPlanArtifacts(plan, selectedAgents), "ACTIVE");
        List<String> outputs = executeWorkflowSteps(contextMessages, defaultModel, selectedAgents, adapterRegistry, usageRecorder,
                plan != null && plan.isParallel());
        recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "specialists_completed",
                buildOutputsArtifacts(selectedAgents, outputs), "ACTIVE");
        String finalAnswer = synthesize(contextMessages, defaultModel, manager, selectedAgents, outputs, adapterRegistry, plan);
        recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "synthesized",
                buildFinalArtifacts(plan, selectedAgents, outputs, finalAnswer), "ACTIVE");
        return new TeamWorkflowResult(plan, selectedAgents, outputs, finalAnswer);
    }

    private List<TeamAgentRuntime> selectAgents(List<TeamAgentRuntime> teamAgents, MultiAgentPlan plan) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            return List.of();
        }
        if (plan == null || plan.getSelectedAgentIds() == null || plan.getSelectedAgentIds().isEmpty()) {
            return teamAgents.size() <= 2 ? teamAgents : teamAgents.subList(0, 2);
        }
        List<TeamAgentRuntime> selected = new ArrayList<>();
        Set<String> wanted = new HashSet<>(plan.getSelectedAgentIds());
        for (TeamAgentRuntime runtime : teamAgents) {
            if (runtime == null || runtime.getAgent() == null) {
                continue;
            }
            if (wanted.contains(runtime.getAgent().getAgentId())) {
                selected.add(runtime);
            }
        }
        if (selected.isEmpty()) {
            return teamAgents.size() <= 2 ? teamAgents : teamAgents.subList(0, 2);
        }
        return selected;
    }

    private List<String> executeWorkflowSteps(List<LlmMessage> contextMessages,
                                              String defaultModel,
                                              List<TeamAgentRuntime> selectedAgents,
                                              LlmAdapterRegistry adapterRegistry,
                                              ToolUsageRecorder usageRecorder,
                                              boolean parallel) {
        if (selectedAgents == null || selectedAgents.isEmpty()) {
            return List.of();
        }
        if (parallel) {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (TeamAgentRuntime runtime : selectedAgents) {
                futures.add(supplyWithTimeout(() ->
                                runAgent(runtime, contextMessages, defaultModel, adapterRegistry, usageRecorder),
                        "team-agent-" + runtime.getAgent().getAgentId()));
            }
            List<String> outputs = new ArrayList<>();
            for (CompletableFuture<String> future : futures) {
                outputs.add(future.join());
            }
            return outputs;
        }
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < selectedAgents.size(); i++) {
            TeamAgentRuntime runtime = selectedAgents.get(i);
            List<LlmMessage> stepMessages = buildWorkflowStepMessages(contextMessages, selectedAgents, outputs, i);
            outputs.add(runAgent(runtime, stepMessages, defaultModel, adapterRegistry, usageRecorder));
        }
        return outputs;
    }

    private List<LlmMessage> buildWorkflowStepMessages(List<LlmMessage> baseMessages,
                                                       List<TeamAgentRuntime> selectedAgents,
                                                       List<String> priorOutputs,
                                                       int stepIndex) {
        List<LlmMessage> messages = new ArrayList<>(baseMessages);
        if (priorOutputs.isEmpty()) {
            return messages;
        }
        StringBuilder sb = new StringBuilder("Prior specialist findings:\n");
        for (int i = 0; i < priorOutputs.size(); i++) {
            TeamAgentRuntime runtime = selectedAgents.get(i);
            String name = runtime != null && runtime.getAgent() != null ? safe(runtime.getAgent().getName()) : "Agent-" + (i + 1);
            sb.append("- ").append(name).append(": ").append(compact(priorOutputs.get(i), 260)).append('\n');
        }
        sb.append("Use prior findings as context. Do not repeat them unless needed.");
        messages.add(new LlmMessage("system", sb.toString()));
        messages.add(new LlmMessage("system", "Workflow step " + (stepIndex + 1) + " of " + selectedAgents.size() + ". Provide only the specialist contribution for this step."));
        return messages;
    }

    private String runAgent(TeamAgentRuntime runtime,
                            List<LlmMessage> contextMessages,
                            String defaultModel,
                            LlmAdapterRegistry adapterRegistry,
                            ToolUsageRecorder usageRecorder) {
        if (runtime == null || runtime.getAgent() == null) {
            return "";
        }
        Agent agent = runtime.getAgent();
        String model = agent.getModel() == null || agent.getModel().isBlank() ? defaultModel : agent.getModel();
        LlmAdapter adapter = adapterRegistry.getAdapter(model);
        String draft = safe(adapter.chat(buildTeamMessages(runtime, contextMessages), model));
        String skillAnswer = trySkillCall(draft, runtime, adapter, model, contextMessages, usageRecorder);
        return skillAnswer != null ? skillAnswer : draft;
    }

    private String synthesize(List<LlmMessage> contextMessages,
                              String defaultModel,
                              Agent manager,
                              List<TeamAgentRuntime> selectedAgents,
                              List<String> outputs,
                              LlmAdapterRegistry adapterRegistry,
                              MultiAgentPlan plan) {
        String managerSystem = manager != null && manager.getInstructions() != null && !manager.getInstructions().isBlank()
                ? manager.getInstructions()
                : "You are Manager Agent. Synthesize the selected specialist outputs into one final answer.";
        String managerModel = manager != null && manager.getModel() != null && !manager.getModel().isBlank()
                ? manager.getModel()
                : defaultModel;
        LlmAdapter managerAdapter = adapterRegistry.getAdapter(managerModel);

        List<LlmMessage> aggregatorMessages = new ArrayList<>();
        aggregatorMessages.add(new LlmMessage("system", managerSystem
                + "\nUse the specialist outputs below as workflow step results. Merge duplicates, resolve contradictions, and answer directly."));
        aggregatorMessages.addAll(contextMessages);
        aggregatorMessages.add(new LlmMessage("assistant", formatTeamOutputs(selectedAgents, outputs, plan)));
        return safe(managerAdapter.chat(aggregatorMessages, managerModel));
    }

    private List<LlmMessage> buildTeamMessages(TeamAgentRuntime runtime, List<LlmMessage> contextMessages) {
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

    private String trySkillCall(String draft,
                                TeamAgentRuntime runtime,
                                LlmAdapter adapter,
                                String model,
                                List<LlmMessage> contextMessages,
                                ToolUsageRecorder usageRecorder) {
        if (draft == null || runtime == null) {
            return null;
        }
        String trimmed = draft.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
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
            return runSkillFollowup(plan.getSelectedSkillKey(), plan.getNormalizedInput(), runtime, adapter, model, contextMessages, usageRecorder, draft);
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
                                    ToolUsageRecorder usageRecorder,
                                    String draft) {
        String skillModel = runtime.getAgent() != null ? runtime.getAgent().getToolModel() : null;
        String finalInput = normalizeSkillInput(skillKey, input, extractLastUserPrompt(contextMessages));
        SkillExecutionResult result = skillExecutor.execute(new SkillExecutionRequest(skillKey, finalInput, skillModel));
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
        followup.add(new LlmMessage("user", "Skill result:\n" + safe(result.getOutput())
                + "\n\nProvide the final answer based only on this result."));
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

    private String formatTeamOutputs(List<TeamAgentRuntime> agents, List<String> outputs, MultiAgentPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan != null && plan.getReason() != null && !plan.getReason().isBlank()) {
            sb.append("Workflow planner note: ").append(plan.getReason()).append("\n\n");
        }
        int size = Math.min(agents.size(), outputs.size());
        for (int i = 0; i < size; i++) {
            TeamAgentRuntime runtime = agents.get(i);
            Agent agent = runtime != null ? runtime.getAgent() : null;
            String name = agent != null && agent.getName() != null ? agent.getName() : "Agent-" + (i + 1);
            sb.append("Step ").append(i + 1).append(" - ").append(name).append(":\n")
                    .append(outputs.get(i) == null ? "" : outputs.get(i)).append("\n\n");
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

    private CompletableFuture<String> supplyWithTimeout(Supplier<String> supplier, String label) {
        return CompletableFuture.supplyAsync(supplier, agentExecutor)
                .orTimeout(agentTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Agent task timeout/failure: label={}, error={}", label, ex == null ? "unknown" : ex.getMessage());
                    return "";
                });
    }

    private void recordWorkflowProgress(Long userId,
                                        String chatId,
                                        String assistantMessageId,
                                        String goal,
                                        String currentStep,
                                        String artifactsJson,
                                        String status) {
        if (userId == null || chatId == null || assistantMessageId == null) {
            return;
        }
        agentMemoryService.recordWorkflowProgress(userId, chatId, TEAM_WORKFLOW_KEY, goal, currentStep, status, artifactsJson, assistantMessageId);
    }

    private String buildPlanArtifacts(MultiAgentPlan plan, List<TeamAgentRuntime> selectedAgents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "planned");
        payload.put("parallel", plan != null && plan.isParallel());
        payload.put("reason", plan != null ? plan.getReason() : null);
        payload.put("selected_agents", selectedAgents.stream()
                .map(runtime -> runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null)
                .toList());
        return toJson(payload);
    }

    private String buildOutputsArtifacts(List<TeamAgentRuntime> selectedAgents, List<String> outputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "specialists_completed");
        List<Map<String, Object>> steps = new ArrayList<>();
        int size = Math.min(selectedAgents.size(), outputs.size());
        for (int i = 0; i < size; i++) {
            Map<String, Object> step = new LinkedHashMap<>();
            TeamAgentRuntime runtime = selectedAgents.get(i);
            step.put("agent_id", runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null);
            step.put("role", runtime != null ? runtime.getRole() : null);
            step.put("output_excerpt", compact(outputs.get(i), 320));
            steps.add(step);
        }
        payload.put("steps", steps);
        return toJson(payload);
    }

    private String buildFinalArtifacts(MultiAgentPlan plan,
                                       List<TeamAgentRuntime> selectedAgents,
                                       List<String> outputs,
                                       String finalAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "synthesized");
        payload.put("parallel", plan != null && plan.isParallel());
        payload.put("selected_agents", selectedAgents.stream()
                .map(runtime -> runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null)
                .toList());
        payload.put("step_count", outputs == null ? 0 : outputs.size());
        payload.put("final_answer_excerpt", compact(finalAnswer, 600));
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private String compact(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private void emitChunked(FluxSink<String> sink, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int chunkSize = 120;
        for (int i = 0; i < text.length(); i += chunkSize) {
            sink.next(text.substring(i, Math.min(text.length(), i + chunkSize)));
        }
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

    public interface ToolUsageRecorder {
        void record(String model, int promptTokens, int completionTokens);
    }

    private record TeamWorkflowResult(MultiAgentPlan plan,
                                      List<TeamAgentRuntime> selectedAgents,
                                      List<String> outputs,
                                      String finalAnswer) {}
}