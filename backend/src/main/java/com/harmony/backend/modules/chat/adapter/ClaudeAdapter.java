package com.harmony.backend.modules.chat.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeAdapter implements LlmAdapter {

    @Value("${app.ai.claude.key:}")
    private String apiKey;

    @Value("${app.ai.claude.base-url:https://api.gptsapi.net}")
    private String baseUrl;


    @Value("${app.ai.claude.max-tokens:1024}")
    private int maxTokens;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toLowerCase();
        return normalized.startsWith("claude");
    }

    @Override
    public Flux<String> streamChat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
        Map<String, Object> payload = buildPayload(messages, model, true);

        return webClient.post()
                .uri(resolveMessagesPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("x-api-key", apiKey)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::extractStreamContent)
                .filter(content -> content != null && !content.isEmpty());
    }

    @Override
    public String chat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
        Map<String, Object> payload = buildPayload(messages, model, false);

        String response = webClient.post()
                .uri(resolveMessagesPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {
            return "";
        }
        return extractSyncContent(response);
    }

    private String resolveMessagesPath() {
        String normalized = baseUrl == null ? "" : baseUrl.trim().toLowerCase();
        if (normalized.endsWith("/v1")) {
            return "/messages";
        }
        return "/v1/messages";
    }

    private Map<String, Object> buildPayload(List<LlmMessage> messages, String model, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("max_tokens", Math.max(1, maxTokens));

        String system = null;
        List<Map<String, String>> converted = new java.util.ArrayList<>();
        for (LlmMessage message : messages) {
            if (message == null || message.getContent() == null) {
                continue;
            }
            String role = message.getRole();
            if ("system".equalsIgnoreCase(role)) {
                if (system == null || system.isBlank()) {
                    system = message.getContent();
                }
                continue;
            }
            if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) {
                continue;
            }
            converted.add(Map.of("role", role.toLowerCase(), "content", message.getContent()));
        }
        payload.put("messages", converted);
        if (system != null && !system.isBlank()) {
            payload.put("system", system);
        }
        return payload;
    }

    private Flux<String> extractStreamContent(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return Flux.empty();
        }
        String[] lines = chunk.split("\n");
        return Flux.fromArray(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .flatMap(line -> {
                    String payload = line.startsWith("data:")
                            ? line.substring(5).trim()
                            : (line.startsWith("{") ? line : null);
                    if (payload == null || payload.isEmpty() || "[DONE]".equals(payload)) {
                        return Flux.empty();
                    }
                    return Mono.fromCallable(() -> {
                        JsonNode root = objectMapper.readTree(payload);
                        JsonNode deltaText = root.path("delta").path("text");
                        if (!deltaText.isMissingNode()) {
                            return deltaText.asText("");
                        }
                        JsonNode contentNode = root.path("content");
                        if (contentNode.isArray() && contentNode.size() > 0) {
                            return contentNode.get(0).path("text").asText("");
                        }
                        return "";
                    }).onErrorReturn("");
                })
                .filter(content -> content != null && !content.isEmpty());
    }

    private String extractSyncContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("content");
            if (contentNode.isArray() && contentNode.size() > 0) {
                return contentNode.get(0).path("text").asText("");
            }
            JsonNode completion = root.path("completion");
            return completion.isMissingNode() ? "" : completion.asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
