package com.harmony.backend.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.agent.planner.MultiAgentPlan;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanStep;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanner;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.AgentRun;
import com.harmony.backend.common.entity.AgentRunStep;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiAgentOrchestratorTest {

    @Test
    void run_should_delegate_to_adapter_chat() {
        MultiAgentOrchestrator orchestrator = newOrchestrator(mock(MultiAgentPlanner.class), mock(AgentRunStateService.class), new AgentAutonomyPolicy(8, 4, 30_000, true));
        RecordingAdapter adapter = new RecordingAdapter().withResponses("FINAL: answer");

        String result = orchestrator.run(List.of(new LlmMessage("user", "Explain CAP theorem in 3 bullet points.")), "deepseek-chat", adapter);

        assertEquals("FINAL: answer", result);
        assertEquals(1, adapter.calls.size());
    }

    @Test
    void stream_should_emit_chunks_from_adapter() {
        MultiAgentOrchestrator orchestrator = newOrchestrator(mock(MultiAgentPlanner.class), mock(AgentRunStateService.class), new AgentAutonomyPolicy(8, 4, 30_000, true));
        RecordingAdapter adapter = new RecordingAdapter().withResponses("FINAL RESPONSE");

        List<String> chunks = orchestrator.stream(List.of(new LlmMessage("user", "Test streaming output.")), "deepseek-chat", adapter).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals("FINAL RESPONSE", String.join("", chunks));
    }

    @Test
    void runTeam_should_record_lifecycle_transitions_for_completed_runtime() {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentRunStateService runStateService = mock(AgentRunStateService.class);
        MultiAgentOrchestrator orchestrator = newOrchestrator(planner, runStateService, new AgentAutonomyPolicy(8, 4, 30_000, true));

        RecordingAdapter adapter = new RecordingAdapter().withResponses("specialist-output", "final-output");
        LlmAdapterRegistry adapterRegistry = mock(LlmAdapterRegistry.class);
        when(adapterRegistry.getAdapter(any())).thenReturn(adapter);
        when(planner.plan(any(), eq("deepseek-chat"), any(), any(), eq(adapterRegistry)))
                .thenReturn(new MultiAgentPlan(List.of("analyst-1"), false, "Need one analyst", true, 0.9,
                        List.of(new MultiAgentPlanStep(1, "analyst-1", "SPECIALIST", false, "analyze"))));

        Agent manager = Agent.builder().agentId("manager-1").name("Manager").model("deepseek-chat").build();
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(Agent.builder().agentId("analyst-1").name("Analyst").model("deepseek-chat").instructions("Analyze").build());

        AgentRun run = AgentRun.builder().id(9L).executionId("exec-1").build();
        when(runStateService.createPlannedRun(any(), any(), any(), any(), any(), any())).thenReturn(run);
        when(runStateService.createRunningStep(eq(run), eq(1), eq("planning"), any(), any(), any())).thenReturn(AgentRunStep.builder().id(11L).build());
        when(runStateService.createRunningStep(eq(run), eq(2), eq("specialist"), eq("analyst-1"), any(), any())).thenReturn(AgentRunStep.builder().id(12L).build());
        when(runStateService.createRunningStep(eq(run), eq(3), eq("synthesis"), eq("manager-1"), any(), any())).thenReturn(AgentRunStep.builder().id(13L).build());
        when(runStateService.isCancellationRequested(any())).thenReturn(false);

        String result = orchestrator.runTeam(List.of(new LlmMessage("user", "Summarize the design.")), "deepseek-chat", manager, List.of(runtime), adapterRegistry, null, 1L, "chat-1", "msg-1");

        assertEquals("final-output", result);
        verify(runStateService).markRunCompleted(eq(run), eq("completed"), eq("final-output"), eq(3), eq(0), eq(null), any());
    }

    @Test
    void executeTeamWorkflow_should_return_waiting_when_budget_is_exhausted() {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentRunStateService runStateService = mock(AgentRunStateService.class);
        MultiAgentOrchestrator orchestrator = newOrchestrator(planner, runStateService, new AgentAutonomyPolicy(1, 4, 30_000, true));

        RecordingAdapter adapter = new RecordingAdapter().withResponses("unused");
        LlmAdapterRegistry adapterRegistry = mock(LlmAdapterRegistry.class);
        when(adapterRegistry.getAdapter(any())).thenReturn(adapter);
        when(planner.plan(any(), eq("deepseek-chat"), any(), any(), eq(adapterRegistry)))
                .thenReturn(new MultiAgentPlan(List.of("analyst-1"), false, "Need one analyst", true, 0.9,
                        List.of(new MultiAgentPlanStep(1, "analyst-1", "SPECIALIST", false, "analyze"))));

        Agent manager = Agent.builder().agentId("manager-1").name("Manager").model("deepseek-chat").build();
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(Agent.builder().agentId("analyst-1").name("Analyst").model("deepseek-chat").instructions("Analyze").build());

        AgentRun run = AgentRun.builder().id(9L).executionId("exec-wait").goal("Summarize the design.").build();
        when(runStateService.createPlannedRun(any(), any(), any(), any(), any(), any())).thenReturn(run);
        when(runStateService.createRunningStep(eq(run), eq(1), eq("planning"), any(), any(), any())).thenReturn(AgentRunStep.builder().id(11L).build());

        var result = orchestrator.executeTeamWorkflow(List.of(new LlmMessage("user", "Summarize the design.")), "deepseek-chat",
                manager, List.of(runtime), adapterRegistry, null, 1L, "chat-1", "msg-1");

        assertEquals(AgentRunStatus.WAITING, result.getStatus());
        assertEquals("exec-wait", result.getExecutionId());
        assertEquals("Step budget exhausted", result.getError());
        verify(runStateService).markRunWaiting(eq(run), eq("specialists"), eq("Step budget exhausted"), eq(1), eq(0), any(), any());
    }

    @Test
    void executeTeamWorkflow_should_mark_run_failed_when_synthesis_throws() {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentRunStateService runStateService = mock(AgentRunStateService.class);
        MultiAgentOrchestrator orchestrator = newOrchestrator(planner, runStateService, new AgentAutonomyPolicy(8, 4, 30_000, true));

        ThrowingAdapter adapter = new ThrowingAdapter("specialist-output");
        LlmAdapterRegistry adapterRegistry = mock(LlmAdapterRegistry.class);
        when(adapterRegistry.getAdapter(any())).thenReturn(adapter);
        when(planner.plan(any(), eq("deepseek-chat"), any(), any(), eq(adapterRegistry)))
                .thenReturn(new MultiAgentPlan(List.of("analyst-1"), false, "Need one analyst", true, 0.9,
                        List.of(new MultiAgentPlanStep(1, "analyst-1", "SPECIALIST", false, "analyze"))));

        Agent manager = Agent.builder().agentId("manager-1").name("Manager").model("deepseek-chat").build();
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(Agent.builder().agentId("analyst-1").name("Analyst").model("deepseek-chat").instructions("Analyze").build());

        AgentRun run = AgentRun.builder().id(9L).executionId("exec-failed").build();
        when(runStateService.createPlannedRun(any(), any(), any(), any(), any(), any())).thenReturn(run);
        when(runStateService.createRunningStep(eq(run), eq(1), eq("planning"), any(), any(), any())).thenReturn(AgentRunStep.builder().id(11L).build());
        when(runStateService.createRunningStep(eq(run), eq(2), eq("specialist"), eq("analyst-1"), any(), any())).thenReturn(AgentRunStep.builder().id(12L).build());
        when(runStateService.createRunningStep(eq(run), eq(3), eq("synthesis"), eq("manager-1"), any(), any())).thenReturn(AgentRunStep.builder().id(13L).build());
        when(runStateService.isCancellationRequested(any())).thenReturn(false);

        RuntimeException error = assertThrows(RuntimeException.class, () -> orchestrator.executeTeamWorkflow(
                List.of(new LlmMessage("user", "Summarize the design.")),
                "deepseek-chat",
                manager,
                List.of(runtime),
                adapterRegistry,
                null,
                1L,
                "chat-1",
                "msg-1"));

        assertEquals("synthesis boom", error.getMessage());
        verify(runStateService, atLeastOnce()).markRunFailed(eq(run), eq("failed"), eq("synthesis boom"), anyInt(), eq(0), any(), any());
    }

    @Test
    void resumeTeamWorkflow_should_fail_when_checkpoint_repeats_without_progress() throws Exception {
        MultiAgentPlanner planner = mock(MultiAgentPlanner.class);
        AgentRunStateService runStateService = mock(AgentRunStateService.class);
        MultiAgentOrchestrator orchestrator = newOrchestrator(planner, runStateService, new AgentAutonomyPolicy(1, 4, 30_000, true));

        RecordingAdapter adapter = new RecordingAdapter().withResponses("unused");
        LlmAdapterRegistry adapterRegistry = mock(LlmAdapterRegistry.class);
        when(adapterRegistry.getAdapter(any())).thenReturn(adapter);

        Agent manager = Agent.builder().agentId("manager-1").name("Manager").model("deepseek-chat").build();
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(Agent.builder().agentId("analyst-1").name("Analyst").model("deepseek-chat").instructions("Analyze").build());

        AutonomousCheckpoint checkpoint = new AutonomousCheckpoint(List.of("analyst-1"), List.of(), 0, false, "Need one analyst",
                false, 1, 0, 1, "0|false|1|0|0|Step budget exhausted|");
        AgentRun run = AgentRun.builder()
                .id(9L)
                .executionId("exec-no-progress")
                .userId(1L)
                .chatId("chat-1")
                .assistantMessageId("msg-1")
                .currentStep("specialists")
                .checkpointJson(new ObjectMapper().writeValueAsString(checkpoint))
                .planSummaryJson("{\"phase\":\"planned\"}")
                .build();
        when(runStateService.findByExecutionId("exec-no-progress")).thenReturn(run);
        when(runStateService.isCancellationRequested("exec-no-progress")).thenReturn(false);

        var result = orchestrator.resumeTeamWorkflow("exec-no-progress",
                List.of(new LlmMessage("user", "Summarize the design.")),
                "deepseek-chat",
                manager,
                List.of(runtime),
                adapterRegistry,
                null);

        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertEquals("No-progress loop detected: Step budget exhausted", result.getError());
        verify(runStateService).markRunFailed(eq(run), eq("specialists"), eq("No-progress loop detected: Step budget exhausted"), eq(1), eq(0), any(), any());
    }

    private MultiAgentOrchestrator newOrchestrator(MultiAgentPlanner planner, AgentRunStateService runStateService, AgentAutonomyPolicy policy) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new MultiAgentOrchestrator(
                Executors.newSingleThreadExecutor(),
                5,
                mock(com.harmony.backend.ai.skill.SkillExecutor.class),
                mock(com.harmony.backend.ai.skill.SkillPlanner.class),
                mock(com.harmony.backend.ai.skill.AgentSkillRegistry.class),
                objectMapper,
                mock(AgentMemoryService.class),
                planner,
                runStateService,
                new AutonomousAgentRuntimeLoop(objectMapper, policy),
                new AgentPlanResolver(),
                new AgentSpecialistExecutor(mock(com.harmony.backend.ai.skill.SkillExecutor.class), mock(com.harmony.backend.ai.skill.SkillPlanner.class), mock(com.harmony.backend.ai.skill.AgentSkillRegistry.class), objectMapper),
                new AgentSynthesisService()
        );
    }

    private static class RecordingAdapter implements LlmAdapter {
        protected final List<List<LlmMessage>> calls = new ArrayList<>();
        protected final List<String> responses = new ArrayList<>();
        protected final AtomicInteger index = new AtomicInteger(0);

        RecordingAdapter withResponses(String... values) {
            responses.clear();
            for (String value : values) {
                responses.add(value);
            }
            return this;
        }

        @Override
        public boolean supports(String model) {
            return true;
        }

        @Override
        public Flux<String> streamChat(List<LlmMessage> messages, String model) {
            calls.add(new ArrayList<>(messages));
            return Flux.just(chat(messages, model));
        }

        @Override
        public String chat(List<LlmMessage> messages, String model) {
            calls.add(new ArrayList<>(messages));
            int current = index.getAndIncrement();
            return current < responses.size() ? responses.get(current) : "";
        }
    }

    private static class ThrowingAdapter extends RecordingAdapter {
        ThrowingAdapter(String... values) {
            withResponses(values);
        }

        @Override
        public String chat(List<LlmMessage> messages, String model) {
            int current = super.index.getAndIncrement();
            if (current < super.responses.size()) {
                super.calls.add(new ArrayList<>(messages));
                return super.responses.get(current);
            }
            throw new RuntimeException("synthesis boom");
        }
    }
}
