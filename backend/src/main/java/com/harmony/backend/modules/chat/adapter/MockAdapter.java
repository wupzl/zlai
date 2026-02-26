package com.harmony.backend.modules.chat.adapter;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component
public class MockAdapter implements LlmAdapter {

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toLowerCase();
        return normalized.equals("mock-chat") || normalized.startsWith("mock-");
    }

    @Override
    public Flux<String> streamChat(List<LlmMessage> messages, String model) {
        String content = buildResponse(messages);
        return Flux.fromIterable(split(content, 24));
    }

    @Override
    public String chat(List<LlmMessage> messages, String model) {
        return buildResponse(messages);
    }

    private String buildResponse(List<LlmMessage> messages) {
        String lastUser = "";
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                LlmMessage msg = messages.get(i);
                if (msg != null && "user".equalsIgnoreCase(msg.getRole())) {
                    lastUser = msg.getContent() == null ? "" : msg.getContent().trim();
                    break;
                }
            }
        }
        if (lastUser.isBlank()) {
            return "MOCK: Hello. This is a mock response.";
        }
        return "MOCK: " + lastUser;
    }

    private List<String> split(String text, int step) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int size = Math.max(1, step);
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return chunks;
    }
}
