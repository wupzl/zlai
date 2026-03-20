package com.harmony.backend.modules.chat.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractOpenAiCompatibleAdapter implements LlmAdapter {

    protected final WebClient.Builder webClientBuilder;
    protected final ObjectMapper objectMapper;

    @Override
    public Flux<String> streamChat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(getBaseUrl()).build();
        Map<String, Object> payload = buildPayload(messages, model, true);
        return createStreamFlux(webClient, payload, messages, model)
                .filter(content -> content != null && !content.isEmpty());
    }

    @Override
    public String chat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(getBaseUrl()).build();
        Map<String, Object> payload = buildPayload(messages, model, false);

        String response = webClient.post()
                .uri(getChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + getApiKey())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {
            return "";
        }
        return extractSyncContent(response);
    }

    protected Flux<String> createStreamFlux(WebClient webClient,
                                            Map<String, Object> payload,
                                            List<LlmMessage> messages,
                                            String model) {
        return webClient.post()
                .uri(getChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + getApiKey())
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::extractStreamContent);
    }

    protected Map<String, Object> buildPayload(List<LlmMessage> messages, String model, boolean stream) {
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

    protected Flux<String> extractStreamContent(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return Flux.empty();
        }
        String[] lines = chunk.split("\n");
        return Flux.fromArray(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .flatMap(line -> {
                    log.debug("{} stream line: {}", getProviderName(), line);
                    String payload = line.startsWith("data:")
                            ? line.substring(5).trim()
                            : (line.startsWith("{") ? line : null);
                    if (payload == null || payload.isEmpty() || "[DONE]".equals(payload)) {
                        return Flux.empty();
                    }
                    try {
                        JsonNode root = objectMapper.readTree(payload);
                        JsonNode contentNode = root.path("choices").path(0).path("delta").path("content");
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("choices").path(0).path("message").path("content");
                        }
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("choices").path(0).path("text");
                        }
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("content");
                        }
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("result");
                        }
                        String content = contentNode.isMissingNode() ? "" : contentNode.asText("");
                        return content == null || content.isEmpty() ? Flux.<String>empty() : Flux.just(content);
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                });
    }

    protected String extractSyncContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            return contentNode.isMissingNode() ? "" : contentNode.asText("");
        } catch (Exception e) {
            return "";
        }
    }

    protected abstract String getApiKey();

    protected abstract String getBaseUrl();

    protected String getChatPath() {
        return "/v1/chat/completions";
    }

    protected abstract String getProviderName();
}
