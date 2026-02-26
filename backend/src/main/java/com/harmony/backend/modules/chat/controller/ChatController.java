package com.harmony.backend.modules.chat.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.chat.controller.request.ChatRequest;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.config.ChatRateLimitProperties;
import com.harmony.backend.modules.chat.service.ChatService;
import com.harmony.backend.modules.chat.service.ModelPricingService;
import com.harmony.backend.modules.chat.service.SessionService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/chat")
@Slf4j
@RequiredArgsConstructor
@Timed(value = "chat.controller", description = "Chat API Metrics")
public class ChatController {

    private final SessionService sessionService;
    private final ChatService chatService;
    private final BillingProperties billingProperties;
    private final ChatRateLimitProperties chatRateLimitProperties;
    private final ModelPricingService modelPricingService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;
    @Value("${app.chat.stream-timeout-seconds:90}")
    private int streamTimeoutSeconds;

    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:chat:";
    private static final String SESSION_CACHE_KEY_PREFIX = "session:belong:";
    private static final String REQUEST_COUNT_KEY = "metrics:chat:request_count";
    private static final String TOKEN_CACHE_PREFIX = "user:token:";

    @GetMapping("/{chatId}")
    @Timed(value = "chat.get_history", description = "Get chat history")
    public ApiResponse<Object> getHistory(@PathVariable String chatId,
                                          HttpServletRequest request) {
        incrementRequestCount();

        Long userId = getCurrentUserId(request);

        String cacheKey = SESSION_CACHE_KEY_PREFIX + userId + ":" + chatId;
        Boolean cachedBelong = (Boolean) redisTemplate.opsForValue().get(cacheKey);

        if (cachedBelong != null && !cachedBelong) {
            log.warn("Cache denied access: userId={}, chatId={}", userId, chatId);
            return ApiResponse.error(401, "Unauthorized");
        }

        boolean belong = sessionService.checkChatBelong(userId, chatId);
        if (belong) {
            redisTemplate.opsForValue().set(cacheKey, true, Duration.ofHours(2));
        } else {
            redisTemplate.opsForValue().set(cacheKey, false, Duration.ofMinutes(5));
            return ApiResponse.error(401, "Unauthorized");
        }

        try {
            Object chatResponse = chatService.agetChatResponse(chatId, null, false);
            return ApiResponse.success(chatResponse);
        } catch (UnsupportedOperationException e) {
            log.warn("Chat history not implemented: {}", e.getMessage());
            return ApiResponse.error(501, "Chat service not implemented");
        } catch (Exception e) {
            log.error("Get chat history failed: chatId={}", chatId, e);
            return ApiResponse.error("Get chat history failed");
        }
    }

    @GetMapping("/models/options")
    public ApiResponse<List<String>> getAvailableModels() {
        List<String> models = billingProperties.getAvailableModels();
        if (models == null || models.isEmpty()) {
            models = billingProperties.getModelLimits().keySet().stream()
                    .sorted()
                    .toList();
        }
        return ApiResponse.success(models);
    }

    @GetMapping("/models/pricing")
    public ApiResponse<List<com.harmony.backend.modules.chat.controller.response.ModelPricingVO>> getModelPricing() {
        return ApiResponse.success(modelPricingService.listPricing());
    }

    @GetMapping("/sessions")
    @Timed(value = "chat.get_sessions", description = "Get chat sessions")
    public ApiResponse<com.harmony.backend.common.response.PageResult<ChatSessionVO>> getSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        Long userId = getCurrentUserId(request);
        int effectiveSize = Math.min(Math.max(size, 1), 50);
        String cacheKey = String.format("user:sessions:v4:%s:page:%s:size:%s",
                userId, page, effectiveSize);

        com.harmony.backend.common.response.PageResult<ChatSessionVO> cached =
                (com.harmony.backend.common.response.PageResult<ChatSessionVO>) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null && cached.getContent() != null) {
            log.debug("Session list cache hit: userId={}", userId);
            return ApiResponse.success(cached);
        }

        List<ChatSessionVO> sessions = sessionService.getChatSessionsByUserId(userId, page, effectiveSize);
        long total = sessionService.countChatSessionsByUserId(userId);
        com.harmony.backend.common.response.PageResult<ChatSessionVO> result =
                new com.harmony.backend.common.response.PageResult<>();
        result.setContent(sessions);
        result.setTotalElements(total);
        result.setPageNumber(page);
        result.setPageSize(effectiveSize);
        result.setTotalPages((int) Math.ceil(total / (double) effectiveSize));
        redisTemplate.opsForValue().set(cacheKey, result, Duration.ofMinutes(10));

        return ApiResponse.success(result);
    }

    @PostMapping("/session")
    public ApiResponse<ChatSessionVO> createSession(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String toolModel,
            HttpServletRequest request) {

        checkRateLimit("create_session", request);

        Long userId = getCurrentUserId(request);
        ChatSessionVO session = sessionService.createSession(userId, title, model, toolModel);

        clearUserSessionCache(userId);

        return ApiResponse.success(session);
    }

    @PutMapping("/session/{chatId}/title")
    public ApiResponse<Object> renameSession(@PathVariable String chatId,
                                             @RequestParam String title,
                                             HttpServletRequest request) {
        Long userId = getCurrentUserId(request);
        if (!sessionService.checkChatBelong(userId, chatId)) {
            return ApiResponse.error(401, "Unauthorized");
        }
        boolean ok = sessionService.renameSession(userId, chatId, title);
        clearUserSessionCache(userId);
        return ok ? ApiResponse.success("success") : ApiResponse.error("Rename session failed");
    }

    @DeleteMapping("/session/{chatId}")
    public ApiResponse<Object> deleteSession(@PathVariable String chatId,
                                             HttpServletRequest request) {
        Long userId = getCurrentUserId(request);
        if (!sessionService.checkChatBelong(userId, chatId)) {
            return ApiResponse.error(401, "Unauthorized");
        }
        boolean ok = sessionService.deleteSession(userId, chatId);
        clearUserSessionCache(userId);
        return ok ? ApiResponse.success("success") : ApiResponse.error("Delete session failed");
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @CircuitBreaker(name = "chatStream", fallbackMethod = "streamFallback")
    @Timed(value = "chat.stream", description = "Stream chat", extraTags = {"type", "stream"})
    public Flux<ServerSentEvent<String>> streamChat(
            HttpServletRequest request,
            @RequestBody ChatRequest chatRequest) {

        return Flux.defer(() -> {
            try {
                checkRateLimit("stream_chat", request);
            } catch (BusinessException e) {
                return errorEventFlux(e.getMessage());
            } catch (Exception e) {
                log.warn("Stream rate limit check failed", e);
                return errorEventFlux("Rate limit check failed");
            }

            Long userId;
            try {
                userId = getCurrentUserId(request);
            } catch (ResponseStatusException e) {
                return errorEventFlux(e.getReason() != null ? e.getReason() : "Unauthorized");
            }

            String chatId = chatRequest.getChatId();
            String prompt = chatRequest.getPrompt();
            String messageId = chatRequest.getMessageId();
            String parentMessageId = chatRequest.getParentMessageId();
            String regenerateFromAssistantMessageId = chatRequest.getRegenerateFromAssistantMessageId();
            String gptId = chatRequest.getGptId();
            String agentId = chatRequest.getAgentId();
            Boolean useRag = chatRequest.getUseRag();
            String ragQuery = chatRequest.getRagQuery();
            Integer ragTopK = chatRequest.getRagTopK();
            String model = chatRequest.getModel();
            String toolModel = chatRequest.getToolModel();

            boolean regenerateAssistant = regenerateFromAssistantMessageId != null
                    && !regenerateFromAssistantMessageId.isBlank();
            if (!regenerateAssistant) {
                if (prompt == null || prompt.trim().isEmpty()) {
                    return errorEventFlux("Message content is empty");
                }
                if (prompt.length() > 4000) {
                    return errorEventFlux("Message content too long");
                }
            }

            long startTime = System.currentTimeMillis();
            boolean isNewSession = (chatId == null || chatId.isEmpty());

            Mono<String> chatIdMono;
            if (isNewSession) {
                chatIdMono = chatService.createNewSessionAndGetChatId(
                        userId, prompt, model, toolModel, gptId, agentId, useRag, ragQuery, ragTopK);
            } else {
                String cacheKey = SESSION_CACHE_KEY_PREFIX + userId + ":" + chatId;
                Boolean cachedBelong = (Boolean) redisTemplate.opsForValue().get(cacheKey);

                if (cachedBelong != null && !cachedBelong) {
                    return errorEventFlux("Unauthorized");
                }

                boolean belong = cachedBelong != null ? cachedBelong :
                        sessionService.checkChatBelong(userId, chatId);

                if (!belong) {
                    redisTemplate.opsForValue().set(cacheKey, false, Duration.ofMinutes(5));
                    return errorEventFlux("Unauthorized");
                }

                redisTemplate.opsForValue().set(cacheKey, true, Duration.ofHours(2));
                chatIdMono = Mono.just(chatId);
            }
            String finalModel = model;

            return chatIdMono.flatMapMany(finalChatId -> {
                        Flux<ServerSentEvent<String>> initSSE = Flux.empty();
                        if (isNewSession) {
                            clearUserSessionCache(userId);
                            initSSE = Flux.just(ServerSentEvent.<String>builder()
                                    .event("session_created")
                                    .data("{\"chatId\":\"" + finalChatId + "\"}")
                                    .build());
                        }

                        Flux<String> rawStream;
                        if (regenerateAssistant) {
                            if (isNewSession) {
                                return errorEventFlux("Cannot regenerate on a new session");
                            }
                            rawStream = chatService.regenerateAssistant(
                                    userId, finalChatId, regenerateFromAssistantMessageId, gptId, agentId, finalModel,
                                    toolModel,
                                    useRag, ragQuery, ragTopK);
                        } else {
                            rawStream = chatService.chat(
                                    userId, finalChatId, prompt, parentMessageId, messageId, gptId, agentId, finalModel,
                                    toolModel,
                                    useRag, ragQuery, ragTopK);
                        }
                        if (rawStream == null) {
                            return errorEventFlux("Streaming not supported");
                        }
                        rawStream = rawStream.timeout(Duration.ofSeconds(streamTimeoutSeconds));

                        Flux<ServerSentEvent<String>> chatStream = rawStream
                                .map(chunk -> {
                                    String escapedChunk = escapeJson(chunk);
                                    return ServerSentEvent.<String>builder()
                                            .event("message_chunk")
                                            .data("{\"role\":\"assistant\",\"content\":\"" + escapedChunk + "\"}")
                                            .build();
                                })
                                .onBackpressureBuffer(100,
                                        buffer -> log.warn("Backpressure buffer overflow, drop latest"),
                                        reactor.core.publisher.BufferOverflowStrategy.DROP_LATEST)
                                .doOnComplete(() -> {
                                    long duration = System.currentTimeMillis() - startTime;
                                    log.info("Stream chat completed: userId={}, chatId={}, duration={}ms",
                                            userId, finalChatId, duration);
                                    recordMetrics(userId, finalChatId, duration, true);
                                })
                                .doOnError(error -> {
                                    log.error("Stream chat error: userId={}, chatId={}", userId, finalChatId, error);
                                    recordMetrics(userId, finalChatId,
                                            System.currentTimeMillis() - startTime, false);
                                })
                                .onErrorResume(error -> errorEventFlux(resolveStreamError(error)));

                        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                                .event("done")
                                .data("{\"success\":true}")
                                .build();

                        return initSSE
                                .concatWith(chatStream)
                                .concatWithValues(doneEvent)
                                .doFinally(signal -> clearUserSessionCache(userId));
                    })
                    .onErrorResume(error -> errorEventFlux(resolveStreamError(error)));
        });
    }

    @PostMapping("/message")
    @Timed(value = "chat.message", description = "Sync chat", extraTags = {"type", "sync"})
    public ApiResponse<Object> sendMessage(
            @RequestBody ChatRequest chatRequest,
            HttpServletRequest request) {

        checkRateLimit("send_message", request);

        Long userId = getCurrentUserId(request);
        String chatId = chatRequest.getChatId();
        String prompt = chatRequest.getPrompt();
        String model = chatRequest.getModel();
        String gptId = chatRequest.getGptId();
        String agentId = chatRequest.getAgentId();
        Boolean useRag = chatRequest.getUseRag();
        String ragQuery = chatRequest.getRagQuery();
        Integer ragTopK = chatRequest.getRagTopK();
        String parentMessageId = chatRequest.getParentMessageId();
        String messageId = chatRequest.getMessageId();
        String toolModel = chatRequest.getToolModel();

        if (prompt == null || prompt.trim().isEmpty()) {
            return ApiResponse.error("Message content is empty");
        }
        if (prompt.length() > 4000) {
            return ApiResponse.error("Message content too long");
        }

        if (chatId != null && !chatId.isEmpty()) {
            String cacheKey = SESSION_CACHE_KEY_PREFIX + userId + ":" + chatId;
            Boolean cachedBelong = (Boolean) redisTemplate.opsForValue().get(cacheKey);

            if (cachedBelong != null && !cachedBelong) {
                return ApiResponse.error(401, "Unauthorized");
            }

            boolean belong = cachedBelong != null ? cachedBelong :
                    sessionService.checkChatBelong(userId, chatId);

            if (!belong) {
                redisTemplate.opsForValue().set(cacheKey, false, Duration.ofMinutes(5));
                return ApiResponse.error(401, "Unauthorized");
            }

            redisTemplate.opsForValue().set(cacheKey, true, Duration.ofHours(2));
        }

        long startTime = System.currentTimeMillis();
        try {
            Object response = chatService.sendMessage(
                    userId, chatId, prompt, parentMessageId, messageId,
                    gptId, agentId, model, toolModel, useRag, ragQuery, ragTopK);
            long duration = System.currentTimeMillis() - startTime;

            log.info("Sync chat completed: userId={}, duration={}ms", userId, duration);
            recordMetrics(userId, chatId, duration, true);
            clearUserSessionCache(userId);

            return ApiResponse.success(response);
        } catch (UnsupportedOperationException e) {
            return ApiResponse.error(501, "Chat service not implemented");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            recordMetrics(userId, chatId, duration, false);
            throw e;
        }
    }

    public Flux<ServerSentEvent<String>> streamFallback(
            HttpServletRequest request,
            ChatRequest chatRequest,
            Throwable throwable) {

        log.warn("Chat circuit breaker fallback: {}", throwable.getMessage());
        return errorEventFlux("System busy, please retry later");
    }

    private void checkRateLimit(String action, HttpServletRequest request) {
        Long userId = getCurrentUserId(request);
        String ip = getClientIp(request);

        String userKey = RATE_LIMIT_KEY_PREFIX + "user:" + userId + ":" + action;
        String ipKey = RATE_LIMIT_KEY_PREFIX + "ip:" + ip + ":" + action;

        RateLimitConfig config = getRateLimitConfig(action);

        Long userCount = redisTemplate.opsForValue().increment(userKey);
        if (userCount != null && userCount == 1) {
            redisTemplate.expire(userKey, config.windowSeconds, TimeUnit.SECONDS);
        }

        Long ipCount = redisTemplate.opsForValue().increment(ipKey);
        if (ipCount != null && ipCount == 1) {
            redisTemplate.expire(ipKey, config.windowSeconds, TimeUnit.SECONDS);
        }

        if (userCount != null && userCount > config.userLimit) {
            throw new BusinessException("Too many requests");
        }

        if (ipCount != null && ipCount > config.ipLimit) {
            throw new BusinessException("Too many requests from IP");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip != null ? ip.split(",")[0] : "unknown";
    }

    private void clearUserSessionCache(Long userId) {
        String pattern = "user:sessions:*:" + userId + ":*";
        try {
            redisTemplate.delete(redisTemplate.keys(pattern));
            log.debug("Clear session cache: userId={}", userId);
        } catch (Exception e) {
            log.warn("Clear session cache failed: userId={}, error={}", userId, e.getMessage());
        }
    }

    private void recordMetrics(Long userId, String chatId, long duration, boolean success) {
        try {
            String metricKey = String.format("metrics:chat:duration:userId:%s", userId);
            redisTemplate.opsForZSet().add(metricKey, String.valueOf(duration), System.currentTimeMillis());
            redisTemplate.expire(metricKey, Duration.ofDays(1));

            String successKey = "metrics:chat:success_rate";
            redisTemplate.opsForHash().increment(successKey,
                    success ? "success" : "failure", 1);
        } catch (Exception e) {
            log.warn("Record metrics failed: userId={}, error={}", userId, e.getMessage());
        }
    }

    private void incrementRequestCount() {
        try {
            redisTemplate.opsForValue().increment(REQUEST_COUNT_KEY);
            redisTemplate.expire(REQUEST_COUNT_KEY, Duration.ofDays(1));
        } catch (Exception e) {
            log.warn("Increment request count failed: error={}", e.getMessage());
        }
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static class RateLimitConfig {
        int userLimit;
        int ipLimit;
        int windowSeconds;

        RateLimitConfig(int userLimit, int ipLimit, int windowSeconds) {
            this.userLimit = userLimit;
            this.ipLimit = ipLimit;
            this.windowSeconds = windowSeconds;
        }
    }

    private RateLimitConfig getRateLimitConfig(String action) {
        switch (action) {
            case "stream_chat":
                return new RateLimitConfig(
                        chatRateLimitProperties.getStreamUserLimit(),
                        chatRateLimitProperties.getStreamIpLimit(),
                        chatRateLimitProperties.getStreamWindowSeconds());
            case "send_message":
                return new RateLimitConfig(
                        chatRateLimitProperties.getMessageUserLimit(),
                        chatRateLimitProperties.getMessageIpLimit(),
                        chatRateLimitProperties.getMessageWindowSeconds());
            case "create_session":
                return new RateLimitConfig(
                        chatRateLimitProperties.getSessionUserLimit(),
                        chatRateLimitProperties.getSessionIpLimit(),
                        chatRateLimitProperties.getSessionWindowSeconds());
            default:
                return new RateLimitConfig(
                        chatRateLimitProperties.getDefaultUserLimit(),
                        chatRateLimitProperties.getDefaultIpLimit(),
                        chatRateLimitProperties.getDefaultWindowSeconds());
        }
    }

    private Long getCurrentUserId(HttpServletRequest request) {
        Long userIdFromContext = RequestUtils.getCurrentUserId();
        if (userIdFromContext != null) {
            return userIdFromContext;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }

        String token = authHeader.substring(7).trim();
        try {
            String userKey = TOKEN_CACHE_PREFIX + token;
            Long userId = (Long) redisTemplate.opsForValue().get(userKey);

            if (userId != null) {
                return userId;
            }

            userId = jwtUtil.getInternalUserIdFromToken(token, false);
            if (userId == null) {
                log.warn("JWT parse failed");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
            }

            var decodedJWT = jwtUtil.verifyAccessToken(token);
            if (decodedJWT == null) {
                log.warn("JWT verify failed");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
            }

            long remainingSeconds = jwtUtil.getTokenRemainingTime(token, false);
            if (remainingSeconds > 0) {
                long cacheSeconds = (long) (remainingSeconds * 0.9);
                redisTemplate.opsForValue().set(
                        userKey,
                        userId,
                        Duration.ofSeconds(cacheSeconds)
                );
                log.debug("Cache token mapping: userId={}, remainingSeconds={}s", userId, cacheSeconds);
            }

            return userId;

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("JWT parse error: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
    }

    private Flux<ServerSentEvent<String>> errorEventFlux(String message) {
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data("{\"error\":\"" + escapeJson(message) + "\"}")
                .build());
    }

    private String resolveStreamError(Throwable error) {
        if (error instanceof BusinessException) {
            return error.getMessage();
        }
        if (error instanceof ResponseStatusException) {
            String reason = ((ResponseStatusException) error).getReason();
            return reason != null ? reason : "Unauthorized";
        }
        if (error instanceof UnsupportedOperationException) {
            return "Chat service not implemented";
        }
        if (error instanceof TimeoutException) {
            return "Stream timeout";
        }
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return "Stream failed";
    }
}
