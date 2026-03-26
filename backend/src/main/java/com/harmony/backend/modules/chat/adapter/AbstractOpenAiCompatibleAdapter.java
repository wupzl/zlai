package com.harmony.backend.modules.chat.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
        logRequestSummary(model, messages, true);
        return createStreamFlux(webClient, payload, messages, model)
                .filter(content -> content != null && !content.isEmpty());
    }

    @Override
    public String chat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(getBaseUrl()).build();
        Map<String, Object> payload = buildPayload(messages, model, false);
        logRequestSummary(model, messages, false);

        String response = webClient.post()
                .uri(getChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + getApiKey())
                .bodyValue(payload)
                .exchangeToMono(clientResponse -> handleStringResponse(clientResponse, model, messages, false))
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
                .exchangeToFlux(clientResponse -> handleStreamResponse(clientResponse, model, messages))
                .flatMap(this::extractStreamContent);
    }

    private Mono<String> handleStringResponse(ClientResponse response,
                                              String model,
                                              List<LlmMessage> messages,
                                              boolean stream) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(String.class).defaultIfEmpty("");
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(buildClientException(response, body, model, messages, stream)));
    }

    private Flux<String> handleStreamResponse(ClientResponse response,
                                              String model,
                                              List<LlmMessage> messages) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToFlux(String.class);
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMapMany(body -> Flux.error(buildClientException(response, body, model, messages, true)));
    }

    private RuntimeException buildClientException(ClientResponse response,
                                                  String body,
                                                  String model,
                                                  List<LlmMessage> messages,
                                                  boolean stream) {
        String excerpt = compact(body, 1200);
        log.error("{} API request failed: status={}, model={}, stream={}, messageCount={}, body={}",
                getProviderName(),
                response.statusCode().value(),
                model,
                stream,
                messages == null ? 0 : messages.size(),
                excerpt);
        String detail = extractErrorMessage(body);
        String summary = String.format("%s API request failed: status=%s, model=%s, stream=%s, detail=%s",
                getProviderName(),
                response.statusCode().value(),
                model,
                stream,
                detail);
        return new IllegalStateException(summary);
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

    protected void logRequestSummary(String model, List<LlmMessage> messages, boolean stream) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("{} request: model={}, stream={}, messageCount={}, roles={}",
                getProviderName(),
                model,
                stream,
                messages == null ? 0 : messages.size(),
                messages == null ? List.of() : messages.stream().map(LlmMessage::getRole).toList());
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "empty error body";
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isObject()) {
                JsonNode message = error.path("message");
                if (!message.isMissingNode() && !message.isNull() && !message.asText("").isBlank()) {
                    return compact(message.asText(""), 400);
                }
                if (error.hasNonNull("type")) {
                    return compact(error.toString(), 400);
                }
            }
            if (root.hasNonNull("message")) {
                return compact(root.path("message").asText(""), 400);
            }
        } catch (Exception ignored) {
        }
        return compact(body, 400);
    }

    private String compact(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, max));
    }
}
