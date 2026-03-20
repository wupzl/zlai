package com.harmony.backend.ai.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.planner.MultiAgentPlanner;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.ai.skill.SkillPlanner;
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class MultiAgentOrchestratorTest {

    @Test
    void run_should_delegate_to_adapter_chat() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                Executors.newSingleThreadExecutor(),
                5,
                mock(SkillExecutor.class),
                mock(SkillPlanner.class),
                mock(AgentSkillRegistry.class),
                new ObjectMapper(),
                mock(AgentMemoryService.class),
                mock(MultiAgentPlanner.class));
        RecordingAdapter adapter = new RecordingAdapter().withResponses("FINAL: answer");

        List<LlmMessage> context = List.of(
                new LlmMessage("user", "Explain CAP theorem in 3 bullet points.")
        );

        String result = orchestrator.run(context, "deepseek-chat", adapter);

        assertEquals("FINAL: answer", result);
        assertEquals(1, adapter.calls.size());
        assertEquals("user", adapter.calls.get(0).get(0).getRole());
    }

    @Test
    void run_should_return_safe_trimmed_result() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                Executors.newSingleThreadExecutor(),
                5,
                mock(SkillExecutor.class),
                mock(SkillPlanner.class),
                mock(AgentSkillRegistry.class),
                new ObjectMapper(),
                mock(AgentMemoryService.class),
                mock(MultiAgentPlanner.class));
        RecordingAdapter adapter = new RecordingAdapter().withResponses("   draft answer   ");

        String result = orchestrator.run(List.of(new LlmMessage("user", "Summarize the note.")), "deepseek-chat", adapter);

        assertEquals("draft answer", result);
    }

    @Test
    void stream_should_emit_chunks_from_adapter() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                Executors.newSingleThreadExecutor(),
                5,
                mock(SkillExecutor.class),
                mock(SkillPlanner.class),
                mock(AgentSkillRegistry.class),
                new ObjectMapper(),
                mock(AgentMemoryService.class),
                mock(MultiAgentPlanner.class));
        RecordingAdapter adapter = new RecordingAdapter().withResponses("FINAL RESPONSE");

        List<String> chunks = orchestrator.stream(
                List.of(new LlmMessage("user", "Test streaming output.")),
                "deepseek-chat",
                adapter
        ).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertEquals("FINAL RESPONSE", String.join("", chunks));
    }

    private static class RecordingAdapter implements LlmAdapter {
        private final List<List<LlmMessage>> calls = new ArrayList<>();
        private final List<String> responses = new ArrayList<>();
        private final AtomicInteger index = new AtomicInteger(0);

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
}
