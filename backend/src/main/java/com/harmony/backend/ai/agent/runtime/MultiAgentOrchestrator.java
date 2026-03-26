package com.harmony.backend.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.agent.planner.MultiAgentPlan;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanStep;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanner;
import com.harmony.backend.ai.runtime.AutonomousAgentExecutionResult;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.AgentRun;
import com.harmony.backend.common.entity.AgentRunStep;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MultiAgentOrchestrator {

    private static final String TEAM_WORKFLOW_KEY = "multi_agent_team_workflow";

    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;
    private final MultiAgentPlanner multiAgentPlanner;
    private final AgentRunStateService agentRunStateService;
    private final AutonomousAgentRuntimeLoop runtimeLoop;
    private final AgentPlanResolver agentPlanResolver;
    private final AgentSpecialistExecutor specialistExecutor;
    private final AgentSynthesisService synthesisService;

    public MultiAgentOrchestrator(@Qualifier("agentExecutor") java.util.concurrent.Executor agentExecutor,
                                  @Value("${app.agents.multiagent-timeout-seconds:20}") long agentTimeoutSeconds,
                                  com.harmony.backend.ai.skill.SkillExecutor skillExecutor,
                                  com.harmony.backend.ai.skill.SkillPlanner skillPlanner,
                                  com.harmony.backend.ai.skill.AgentSkillRegistry skillRegistry,
                                  ObjectMapper objectMapper,
                                  AgentMemoryService agentMemoryService,
                                  MultiAgentPlanner multiAgentPlanner,
                                  AgentRunStateService agentRunStateService,
                                  AutonomousAgentRuntimeLoop runtimeLoop,
                                  AgentPlanResolver agentPlanResolver,
                                  AgentSpecialistExecutor specialistExecutor,
                                  AgentSynthesisService synthesisService) {
        this.objectMapper = objectMapper;
        this.agentMemoryService = agentMemoryService;
        this.multiAgentPlanner = multiAgentPlanner;
        this.agentRunStateService = agentRunStateService;
        this.runtimeLoop = runtimeLoop;
        this.agentPlanResolver = agentPlanResolver;
        this.specialistExecutor = specialistExecutor;
        this.synthesisService = synthesisService;
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
            return run(contextMessages, defaultModel, adapterRegistry.getAdapter(defaultModel));
        }
        AutonomousAgentExecutionResult workflow = executeTeamWorkflow(contextMessages, defaultModel, manager, teamAgents,
                adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return safe(workflow.getOutput());
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
            return stream(contextMessages, defaultModel, adapterRegistry.getAdapter(defaultModel));
        }
        AutonomousAgentExecutionResult workflow = executeTeamWorkflow(contextMessages, defaultModel, manager, teamAgents,
                adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return Flux.create(sink -> {
            emitChunked(sink, workflow.getOutput());
            sink.complete();
        });
    }

    AutonomousAgentExecutionResult executeTeamWorkflow(List<LlmMessage> contextMessages,
                                                       String defaultModel,
                                                       Agent manager,
                                                       List<TeamAgentRuntime> teamAgents,
                                                       LlmAdapterRegistry adapterRegistry,
                                                       ToolUsageRecorder usageRecorder,
                                                       Long userId,
                                                       String chatId,
                                                       String assistantMessageId) {
        AgentRun run = agentRunStateService.createPlannedRun(userId, chatId, assistantMessageId,
                manager != null ? manager.getAgentId() : null, resolveManagerModel(defaultModel, manager), extractLastUserPrompt(contextMessages));
        try {
            AgentRunStep planningStep = agentRunStateService.createRunningStep(run, 1, "planning",
                    manager != null ? manager.getAgentId() : null, compact(extractLastUserPrompt(contextMessages), 320), null);
            MultiAgentPlan plan = multiAgentPlanner.plan(contextMessages, defaultModel, manager, teamAgents, adapterRegistry);
            if (plan != null) {
                plan.deriveStepsIfMissing();
            }
            AgentPlanResolver.ResolvedAgentPlan resolvedPlan = agentPlanResolver.resolve(teamAgents, plan);
            String planSummaryJson = buildPlanArtifacts(plan, resolvedPlan);
            agentRunStateService.markStepCompleted(planningStep, compact(plan != null ? plan.getReason() : null, 320), planSummaryJson);
            AutonomousCheckpoint checkpoint = runtimeLoop.newCheckpoint(resolvedPlan.selectedAgents(), resolvedPlan.parallel(), resolvedPlan.reason(), 1, 0);
            agentRunStateService.markRunRunning(run, "specialists", planSummaryJson, runtimeLoop.writeCheckpoint(checkpoint), 1, 0);
            recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "planned", planSummaryJson, "ACTIVE");
            return continueTeamWorkflow(run, contextMessages, defaultModel, manager, resolvedPlan, adapterRegistry, usageRecorder,
                    userId, chatId, assistantMessageId, planSummaryJson, checkpoint);
        } catch (Exception e) {
            agentRunStateService.markRunFailed(run, "failed", safe(e.getMessage()), safeStepCount(run), safeToolCallCount(run), run.getCheckpointJson(), run.getPlanSummaryJson());
            throw e;
        }
    }

    AutonomousAgentExecutionResult resumeTeamWorkflow(String executionId,
                                                      List<LlmMessage> contextMessages,
                                                      String defaultModel,
                                                      Agent manager,
                                                      List<TeamAgentRuntime> teamAgents,
                                                      LlmAdapterRegistry adapterRegistry,
                                                      ToolUsageRecorder usageRecorder) {
        AgentRun run = agentRunStateService.findByExecutionId(executionId);
        if (run == null) {
            return AutonomousAgentExecutionResult.failed("Agent run not found", resolveManagerModel(defaultModel, manager), executionId, AgentRunStatus.FAILED, 0);
        }
        if (Boolean.TRUE.equals(run.getCancelRequested())) {
            agentRunStateService.markRunCancelled(run, run.getCurrentStep(), "Cancellation requested", safeStepCount(run), safeToolCallCount(run), run.getCheckpointJson(), run.getPlanSummaryJson());
            return AutonomousAgentExecutionResult.cancelled(run.getExecutionId(), resolveManagerModel(defaultModel, manager), safeStepCount(run), "Cancellation requested");
        }
        AutonomousCheckpoint checkpoint = runtimeLoop.readCheckpoint(run.getCheckpointJson());
        if (checkpoint == null) {
            agentRunStateService.markRunFailed(run, "resume", "Missing checkpoint", safeStepCount(run), safeToolCallCount(run), run.getCheckpointJson(), run.getPlanSummaryJson());
            return AutonomousAgentExecutionResult.failed("Missing checkpoint", resolveManagerModel(defaultModel, manager), executionId, AgentRunStatus.FAILED, safeStepCount(run));
        }
        MultiAgentPlan resumePlan = new MultiAgentPlan(checkpoint.selectedAgentIds(), checkpoint.parallel(), checkpoint.planReason(), false, 0.5);
        resumePlan.deriveStepsIfMissing();
        AgentPlanResolver.ResolvedAgentPlan resolvedPlan = agentPlanResolver.resolve(teamAgents, resumePlan);
        agentRunStateService.markRunResumed(run, checkpoint.synthesisPending() ? "synthesis" : "specialists", checkpoint.stepCount(), checkpoint.toolCallCount(), run.getCheckpointJson());
        return continueTeamWorkflow(run, contextMessages, defaultModel, manager, resolvedPlan, adapterRegistry, usageRecorder,
                run.getUserId(), run.getChatId(), run.getAssistantMessageId(), run.getPlanSummaryJson(), checkpoint);
    }

    void cancelTeamWorkflow(String executionId) {
        agentRunStateService.requestCancellation(executionId);
    }
    private AutonomousAgentExecutionResult continueTeamWorkflow(AgentRun run,
                                                                List<LlmMessage> contextMessages,
                                                                String defaultModel,
                                                                Agent manager,
                                                                AgentPlanResolver.ResolvedAgentPlan resolvedPlan,
                                                                LlmAdapterRegistry adapterRegistry,
                                                                ToolUsageRecorder usageRecorder,
                                                                Long userId,
                                                                String chatId,
                                                                String assistantMessageId,
                                                                String planSummaryJson,
                                                                AutonomousCheckpoint checkpoint) {
        try {
            AutonomousCheckpoint currentCheckpoint = checkpoint;
            if (!currentCheckpoint.synthesisPending()) {
                AutonomousCheckpoint checkpointRef = currentCheckpoint;
                AutonomousLoopOutcome specialistOutcome = runtimeLoop.executeSpecialistSlice(
                        resolvedPlan.selectedAgents(),
                        checkpointRef,
                        System.nanoTime(),
                        () -> agentRunStateService.isCancellationRequested(run.getExecutionId()),
                        (runtime, priorOutputs, specialistIndex, stepOrder) -> {
                            List<LlmMessage> stepMessages = checkpointRef.parallel() ? contextMessages
                                    : buildWorkflowStepMessages(contextMessages, resolvedPlan.selectedAgents(), priorOutputs, specialistIndex);
                            return runAgentStep(run, runtime, stepMessages, defaultModel, adapterRegistry, usageRecorder, stepOrder);
                        }
                );
                if (specialistOutcome.failed()) {
                    return finalizeFailed(run, defaultModel, manager, planSummaryJson, specialistOutcome, "specialists",
                            userId, chatId, assistantMessageId);
                }
                if (specialistOutcome.cancelled()) {
                    return finalizeCancelled(run, defaultModel, manager, planSummaryJson, specialistOutcome, userId, chatId, assistantMessageId);
                }
                if (specialistOutcome.waiting()) {
                    return finalizeWaiting(run, defaultModel, manager, planSummaryJson, specialistOutcome, "specialists", userId, chatId, assistantMessageId);
                }
                currentCheckpoint = runtimeLoop.readCheckpoint(specialistOutcome.checkpointJson());
                recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "specialists_completed",
                        buildOutputsArtifacts(resolvedPlan.selectedAgents(), currentCheckpoint.outputs()), "ACTIVE");
            }

            AutonomousLoopOutcome synthesisGate = runtimeLoop.gateSynthesis(currentCheckpoint, System.nanoTime(),
                    () -> agentRunStateService.isCancellationRequested(run.getExecutionId()));
            if (synthesisGate.failed()) {
                return finalizeFailed(run, defaultModel, manager, planSummaryJson, synthesisGate, "synthesis",
                        userId, chatId, assistantMessageId);
            }
            if (synthesisGate.cancelled()) {
                return finalizeCancelled(run, defaultModel, manager, planSummaryJson, synthesisGate, userId, chatId, assistantMessageId);
            }
            if (synthesisGate.waiting()) {
                return finalizeWaiting(run, defaultModel, manager, planSummaryJson, synthesisGate, "synthesis", userId, chatId, assistantMessageId);
            }

            List<String> outputs = currentCheckpoint.outputs();
            int synthesisStepOrder = currentCheckpoint.stepCount() + 1;
            AgentRunStep synthesisStep = agentRunStateService.createRunningStep(run, synthesisStepOrder, "synthesis",
                    manager != null ? manager.getAgentId() : null,
                    compact(synthesisService.formatTeamOutputs(resolvedPlan.selectedAgents(), outputs, resolvedPlan.reason()), 500),
                    buildOutputsArtifacts(resolvedPlan.selectedAgents(), outputs));
            String finalAnswer = synthesisService.synthesize(contextMessages, defaultModel, manager, resolvedPlan.selectedAgents(), outputs, adapterRegistry, resolvedPlan.reason());
            String finalArtifacts = buildFinalArtifacts(resolvedPlan.reason(), resolvedPlan.selectedAgents(), outputs, finalAnswer);
            agentRunStateService.markStepCompleted(synthesisStep, compact(finalAnswer, 320), finalArtifacts);
            recordWorkflowProgress(userId, chatId, assistantMessageId, extractLastUserPrompt(contextMessages), "synthesized", finalArtifacts, "ACTIVE");
            agentRunStateService.markRunCompleted(run, "completed", finalAnswer, synthesisStepOrder, currentCheckpoint.toolCallCount(), null, planSummaryJson);
            return AutonomousAgentExecutionResult.completed(finalAnswer, resolveManagerModel(defaultModel, manager), run.getExecutionId(), AgentRunStatus.COMPLETED, synthesisStepOrder);
        } catch (Exception e) {
            agentRunStateService.markRunFailed(run, "failed", safe(e.getMessage()), safeStepCount(run), safeToolCallCount(run), run.getCheckpointJson(), planSummaryJson);
            throw e;
        }
    }

    private AutonomousAgentExecutionResult finalizeWaiting(AgentRun run, String defaultModel, Agent manager, String planSummaryJson,
                                                           AutonomousLoopOutcome outcome, String currentStep, Long userId, String chatId, String assistantMessageId) {
        agentRunStateService.markRunWaiting(run, currentStep, outcome.waitReason(), outcome.stepCount(), outcome.toolCallCount(), outcome.checkpointJson(), planSummaryJson);
        recordWorkflowProgress(userId, chatId, assistantMessageId, run.getGoal(), currentStep, outcome.checkpointJson(), AgentRunStatus.WAITING);
        return AutonomousAgentExecutionResult.waiting(run.getExecutionId(), resolveManagerModel(defaultModel, manager), outcome.stepCount(), outcome.waitReason());
    }

    private AutonomousAgentExecutionResult finalizeCancelled(AgentRun run, String defaultModel, Agent manager, String planSummaryJson,
                                                             AutonomousLoopOutcome outcome, Long userId, String chatId, String assistantMessageId) {
        agentRunStateService.markRunCancelled(run, run.getCurrentStep(), outcome.waitReason(), outcome.stepCount(), outcome.toolCallCount(), outcome.checkpointJson(), planSummaryJson);
        recordWorkflowProgress(userId, chatId, assistantMessageId, run.getGoal(), run.getCurrentStep(), outcome.checkpointJson(), AgentRunStatus.CANCELLED);
        return AutonomousAgentExecutionResult.cancelled(run.getExecutionId(), resolveManagerModel(defaultModel, manager), outcome.stepCount(), outcome.waitReason());
    }

    private AutonomousAgentExecutionResult finalizeFailed(AgentRun run, String defaultModel, Agent manager, String planSummaryJson,
                                                          AutonomousLoopOutcome outcome, String currentStep,
                                                          Long userId, String chatId, String assistantMessageId) {
        agentRunStateService.markRunFailed(run, currentStep, outcome.waitReason(), outcome.stepCount(), outcome.toolCallCount(), outcome.checkpointJson(), planSummaryJson);
        recordWorkflowProgress(userId, chatId, assistantMessageId, run.getGoal(), currentStep, outcome.checkpointJson(), AgentRunStatus.FAILED);
        return AutonomousAgentExecutionResult.failed(outcome.waitReason(), resolveManagerModel(defaultModel, manager),
                run.getExecutionId(), AgentRunStatus.FAILED, outcome.stepCount());
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

    private AgentStepExecutionResult runAgentStep(AgentRun run,
                                                  TeamAgentRuntime runtime,
                                                  List<LlmMessage> contextMessages,
                                                  String defaultModel,
                                                  LlmAdapterRegistry adapterRegistry,
                                                  ToolUsageRecorder usageRecorder,
                                                  int stepOrder) {
        String stepKey = "specialist-" + stepOrder;
        AgentRunStep step = agentRunStateService.createRunningStep(run, stepOrder, "specialist",
                runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null,
                compact(extractLastUserPrompt(contextMessages), 320), buildStepArtifacts(runtime));
        try {
            AgentStepExecutionResult result = specialistExecutor.execute(runtime, contextMessages, defaultModel, adapterRegistry, usageRecorder, run.getExecutionId(), stepKey);
            agentRunStateService.markStepCompleted(step, compact(result.output(), 320), buildStepArtifacts(runtime));
            return result;
        } catch (Exception e) {
            agentRunStateService.markStepFailed(step, safe(e.getMessage()), buildStepArtifacts(runtime));
            throw e;
        }
    }
    private void recordWorkflowProgress(Long userId, String chatId, String assistantMessageId, String goal, String currentStep, String artifactsJson, String status) {
        if (userId == null || chatId == null || assistantMessageId == null) {
            return;
        }
        agentMemoryService.recordWorkflowProgress(userId, chatId, TEAM_WORKFLOW_KEY, goal, currentStep, status, artifactsJson, assistantMessageId);
    }

    private String buildPlanArtifacts(MultiAgentPlan plan, AgentPlanResolver.ResolvedAgentPlan resolvedPlan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "planned");
        payload.put("parallel", resolvedPlan.parallel());
        payload.put("reason", resolvedPlan.reason());
        payload.put("selected_agents", resolvedPlan.selectedAgents().stream().map(runtime -> runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null).toList());
        payload.put("steps", resolvedPlan.steps().stream().map(step -> Map.of(
                "order", step.getOrder(),
                "agent_id", step.getAgentId(),
                "step_type", step.getStepType(),
                "depends_on_prior_steps", step.isDependsOnPriorSteps(),
                "objective", step.getObjective() == null ? "" : step.getObjective()
        )).toList());
        payload.put("confidence", plan != null ? plan.getConfidence() : null);
        return toJson(payload);
    }

    private String buildOutputsArtifacts(List<TeamAgentRuntime> selectedAgents, List<String> outputs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "specialists_completed");
        List<Map<String, Object>> steps = new ArrayList<>();
        int size = Math.min(selectedAgents.size(), outputs.size());
        for (int i = 0; i < size; i++) {
            TeamAgentRuntime runtime = selectedAgents.get(i);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("agent_id", runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null);
            step.put("role", runtime != null ? runtime.getRole() : null);
            step.put("output_excerpt", compact(outputs.get(i), 320));
            steps.add(step);
        }
        payload.put("steps", steps);
        return toJson(payload);
    }

    private String buildFinalArtifacts(String planReason, List<TeamAgentRuntime> selectedAgents, List<String> outputs, String finalAnswer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "synthesized");
        payload.put("reason", planReason);
        payload.put("selected_agents", selectedAgents.stream().map(runtime -> runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null).toList());
        payload.put("step_count", outputs == null ? 0 : outputs.size());
        payload.put("final_answer_excerpt", compact(finalAnswer, 600));
        return toJson(payload);
    }

    private String buildStepArtifacts(TeamAgentRuntime runtime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "specialist");
        payload.put("agent_id", runtime != null && runtime.getAgent() != null ? runtime.getAgent().getAgentId() : null);
        payload.put("agent_name", runtime != null && runtime.getAgent() != null ? runtime.getAgent().getName() : null);
        payload.put("role", runtime != null ? runtime.getRole() : null);
        payload.put("skills", runtime != null ? runtime.getSkills() : null);
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
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

    private String resolveManagerModel(String defaultModel, Agent manager) {
        if (manager == null || manager.getModel() == null || manager.getModel().isBlank()) {
            return defaultModel;
        }
        return manager.getModel();
    }

    private int safeStepCount(AgentRun run) {
        return run != null && run.getStepCount() != null ? run.getStepCount() : 0;
    }

    private int safeToolCallCount(AgentRun run) {
        return run != null && run.getToolCallCount() != null ? run.getToolCallCount() : 0;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public interface ToolUsageRecorder {
        void record(String model, int promptTokens, int completionTokens);
    }
}
