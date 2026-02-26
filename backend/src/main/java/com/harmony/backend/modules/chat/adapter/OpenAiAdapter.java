package com.harmony.backend.modules.chat.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.common.constant.AppConfigKeys;
import com.harmony.backend.common.entity.SystemLog;
import com.harmony.backend.common.mapper.SystemLogMapper;
import com.harmony.backend.common.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.web.reactive.function.client.ClientResponse;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiAdapter implements LlmAdapter {

    @Value("${app.ai.openai.key:}")
    private String openAiKey;

    @Value("${app.ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${app.rag.embedding.api-key:}")
    private String embeddingKey;

    @Value("${app.rag.embedding.base-url:https://api.gptsapi.net}")
    private String embeddingBaseUrl;

    @Value("${app.ai.openai.stream-enabled:true}")
    private boolean streamEnabled;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final AppConfigService appConfigService;
    private final SystemLogMapper systemLogMapper;
    private final AtomicInteger streamLogCounter = new AtomicInteger(0);

    @Override
    public boolean supports(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toLowerCase();
        return normalized.startsWith("gpt-");
    }

    @Override
    public Flux<String> streamChat(List<LlmMessage> messages, String model) {
        if (!isStreamingSupported()) {
            log.warn("OpenAI stream disabled or unsupported for baseUrl={}, falling back to non-stream.", resolveBaseUrl());
            return Mono.fromCallable(() -> chat(messages, model))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(text -> Flux.just(text))
                    .filter(content -> content != null && !content.isEmpty());
        }
        WebClient webClient = webClientBuilder.baseUrl(resolveBaseUrl()).build();
        Map<String, Object> payload = buildPayload(messages, model, true);

        Flux<String> stream = webClient.post()
                .uri(resolveChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + resolveApiKey())
                .bodyValue(payload)
                .exchangeToFlux(response -> toStreamFromResponse(response, messages, model));

        return stream.switchIfEmpty(Mono.fromCallable(() -> {
                    log.warn("OpenAI stream returned empty content, falling back to non-stream response.");
                    recordStreamFallback();
                    return chat(messages, model);
                }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(text -> Flux.just(text)))
                .filter(content -> content != null && !content.isEmpty());
    }

    private Flux<String> toStreamFromResponse(ClientResponse response, List<LlmMessage> messages, String model) {
        MediaType contentType = response.headers().contentType().orElse(null);
        boolean sse = contentType != null && (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)
                || "text/event-stream".equalsIgnoreCase(contentType.toString()));
        log.info("OpenAI response content-type: {}", contentType);
        if (sse) {
            return response.bodyToFlux(String.class)
                    .flatMap(this::extractStreamContent)
                    .filter(content -> content != null && !content.isEmpty());
        }
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMapMany(body -> {
                    log.warn("OpenAI response is not SSE, fallback to non-stream body. status={}, contentType={}",
                            response.statusCode(), contentType);
                    String content = extractSyncContent(body);
                    return simulateStream(content);
                });
    }

    private Flux<String> simulateStream(String text) {
        if (text == null || text.isBlank()) {
            return Flux.empty();
        }
        int chunkSize = 120;
        int len = text.length();
        java.util.List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < len; i += chunkSize) {
            chunks.add(text.substring(i, Math.min(len, i + chunkSize)));
        }
        return Flux.fromIterable(chunks);
    }

    @Override
    public String chat(List<LlmMessage> messages, String model) {
        WebClient webClient = webClientBuilder.baseUrl(resolveBaseUrl()).build();
        Map<String, Object> payload = buildPayload(messages, model, false);

        String response = webClient.post()
                .uri(resolveChatPath())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + resolveApiKey())
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {
            return "";
        }

        return extractSyncContent(response);
    }

    private String resolveChatPath() {
        String baseUrl = resolveBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/v1/chat/completions";
        }
        String normalized = baseUrl.trim().toLowerCase();
        if (normalized.endsWith("/v1")) {
            return "/chat/completions";
        }
        return "/v1/chat/completions";
    }

    private String resolveApiKey() {
        if (openAiKey != null && !openAiKey.isBlank()) {
            return openAiKey;
        }
        return embeddingKey == null ? "" : embeddingKey;
    }

    private String resolveBaseUrl() {
        if (openAiBaseUrl != null && !openAiBaseUrl.isBlank()) {
            return openAiBaseUrl;
        }
        return embeddingBaseUrl == null || embeddingBaseUrl.isBlank()
                ? "https://api.gptsapi.net"
                : embeddingBaseUrl;
    }

    private boolean isStreamingSupported() {
        if (!streamEnabled) {
            return false;
        }
        String adminSetting = appConfigService.getValue(AppConfigKeys.OPENAI_STREAM_ENABLED);
        if (adminSetting != null) {
            boolean enabled = Boolean.parseBoolean(adminSetting);
            return enabled;
        }
        String base = resolveBaseUrl();
        if (base == null) {
            return true;
        }
        String normalized = base.toLowerCase();
        return !normalized.contains("gptsapi");
    }

    private void recordStreamFallback() {
        try {
            SystemLog logItem = SystemLog.builder()
                    .operation("OPENAI_STREAM_EMPTY")
                    .module(SystemLog.Module.SYSTEM)
                    .status(SystemLog.Status.FAILED.getValue())
                    .build();
            systemLogMapper.insert(logItem);
        } catch (Exception ignored) {
        }
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
                    log.debug("OpenAI stream line: {}", line);
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
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("choices").path(0).path("text");
                        }
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("content");
                        }
                        if (contentNode.isMissingNode()) {
                            contentNode = root.path("result");
                        }
                        if (contentNode.isMissingNode()) {
                            int count = streamLogCounter.incrementAndGet();
                            if (count <= 5) {
                                log.warn("OpenAI stream payload (no content): {}", payload);
                            }
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
