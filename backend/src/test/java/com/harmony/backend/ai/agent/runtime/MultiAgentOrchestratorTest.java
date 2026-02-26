package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultiAgentOrchestratorTest {

    @Test
    void run_should_call_planner_specialist_critic_in_order() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                java.util.concurrent.Executors.newSingleThreadExecutor(), 5);
        RecordingAdapter adapter = new RecordingAdapter()
                .withResponses("PLAN: steps", "RESEARCH: facts", "CRITIQUE: issues", "FINAL: answer");

        List<LlmMessage> context = List.of(
                new LlmMessage("user", "Explain CAP theorem in 3 bullet points.")
        );

        String result = orchestrator.run(context, "deepseek-chat", adapter);

        assertEquals("FINAL: answer", result);
        assertEquals(4, adapter.calls.size());

        List<LlmMessage> call1 = adapter.calls.get(0);
        List<LlmMessage> call2 = adapter.calls.get(1);
        List<LlmMessage> call3 = adapter.calls.get(2);
        List<LlmMessage> call4 = adapter.calls.get(3);

        assertTrue(call1.get(0).getContent().contains("Planner Agent"));
        assertTrue(call2.get(0).getContent().contains("Researcher Agent"));
        assertTrue(call3.get(0).getContent().contains("Critic Agent"));

        assertEquals("assistant", call4.get(call4.size() - 1).getRole());
        assertTrue(call4.get(call4.size() - 1).getContent().contains("Plan:"));
    }

    @Test
    void run_should_fallback_to_draft_when_critic_blank() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                java.util.concurrent.Executors.newSingleThreadExecutor(), 5);
        RecordingAdapter adapter = new RecordingAdapter()
                .withResponses("PLAN: steps", "RESEARCH: facts", "   ", "   ");

        List<LlmMessage> context = List.of(
                new LlmMessage("user", "Summarize the note.")
        );

        String result = orchestrator.run(context, "deepseek-chat", adapter);

        assertTrue(result.contains("PLAN") || result.contains("RESEARCH") || result.contains("steps"));
    }

    @Test
    void stream_should_emit_chunks() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                java.util.concurrent.Executors.newSingleThreadExecutor(), 5);
        RecordingAdapter adapter = new RecordingAdapter()
                .withResponses("PLAN", "RESEARCH", "CRITIQUE", "FINAL RESPONSE");

        List<LlmMessage> context = List.of(
                new LlmMessage("user", "Test streaming output.")
        );

        List<String> chunks = orchestrator.stream(context, "deepseek-chat", adapter).collectList().block();
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
            for (String v : values) {
                responses.add(v);
            }
            return this;
        }

        @Override
        public boolean supports(String model) {
            return true;
        }

        @Override
        public Flux<String> streamChat(List<LlmMessage> messages, String model) {
            return Flux.just(chat(messages, model));
        }

        @Override
        public String chat(List<LlmMessage> messages, String model) {
            calls.add(new ArrayList<>(messages));
            int i = index.getAndIncrement();
            if (i < responses.size()) {
                return responses.get(i);
            }
            return "";
        }
    }
}
