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
public class DeepSeekAdapter implements LlmAdapter {

    @Value("${app.ai.deepseek.key}")
    private String apiKey;

    @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String model) {
        return model != null && model.startsWith("deepseek");
    }

    @Override
    public Flux<String> streamChat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(baseUrl).build();
        Map<String, Object> payload = buildPayload(messages, model, true);

        return webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + apiKey)
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
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {
            return "";
        }

        return extractSyncContent(response);
    }

    private Map<String, Object> buildPayload(List<LlmMessage> messages, String model, boolean stream) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("messages", messages.stream()
                .map(message -> Map.of(
                        "role", message.getRole(),
                        "content", message.getContent()
                ))
                .toList());
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
                    log.debug("DeepSeek stream line: {}", line);
                    String payload = line.startsWith("data:")
                            ? line.substring(5).trim()
                            : (line.startsWith("{") ? line : null);

                    if (payload == null || payload.isEmpty() || "[DONE]".equals(payload)) {
                        return Flux.empty();
                    }

                    return Mono.fromCallable(() -> {
                        JsonNode root = objectMapper.readTree(payload);
                        JsonNode contentNode = root.path("choices").path(0).path("delta").path("content");
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("choices").path(0).path("message").path("content");
                        }
                        return contentNode.isMissingNode() ? "" : contentNode.asText("");
                    }).onErrorReturn("");
                })
                .filter(content -> content != null && !content.isEmpty());
    }

    private String extractSyncContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            return contentNode.isMissingNode() ? "" : contentNode.asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
