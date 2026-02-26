package com.harmony.backend.modules.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.event.ChatMessageEvent;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.AgentMapper;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.AgentToolDefinition;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.agent.runtime.MultiAgentOrchestrator;
import com.harmony.backend.ai.agent.model.TeamAgentConfig;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.common.mapper.TokenConsumptionMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.TokenCounter;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper, Message> implements ChatService {

    private final SessionMapper sessionMapper;
    private final AgentMapper agentMapper;
    private final GPTMapper gptMapper;
    private final LlmAdapterRegistry adapterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenConsumptionMapper tokenConsumptionMapper;
    private final UserMapper userMapper;
    private final BillingProperties billingProperties;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final AgentToolRegistry toolRegistry;
    private final RagService ragService;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    @Value("${app.chat.context.window-messages:20}")
    private int contextWindowMessages;

    @Value("${app.chat.context.warn-messages:200}")
    private int contextWarnMessages;

    @Override
    public Mono<String> createNewSessionAndGetChatId(Long userId, String prompt, String model, String toolModel, String gptId,
                                                     String agentId, Boolean useRag, String ragQuery, Integer ragTopK) {
        String chatId = UUID.randomUUID().toString();
        if (gptId != null && !gptId.isBlank() && agentId != null && !agentId.isBlank()) {
            throw new BusinessException(400, "Cannot bind both GPT and Agent");
        }
        Gpt gpt = resolveGptForSession(userId, gptId);
        Agent agent = resolveAgentForSession(userId, agentId);
        String finalModel = resolveModelForNewSession(model, gpt, agent);
        String title = buildTitleWithLlm(prompt, finalModel);
        String systemPrompt = buildSystemPrompt(gpt, agent);

        Session session = Session.builder()
                .chatId(chatId)
                .userId(userId)
                .gptId(gpt != null ? gpt.getGptId() : null)
                .agentId(agent != null ? agent.getAgentId() : null)
                .title(title)
                .model(finalModel)
                .toolModel(trimOrNull(toolModel))
                .systemPrompt(systemPrompt)
                .ragEnabled(Boolean.TRUE.equals(useRag))
                .messageCount(0)
                .totalTokens(0)
                .lastActiveTime(LocalDateTime.now())
                .build();
        sessionMapper.insert(session);
        incrementGptUsage(gpt);

        return Mono.just(chatId);
    }

    @Override
    @Transactional
    public Flux<String> chat(Long userId, String chatId, String prompt, String parentMessageId,
                             String messageId, String gptId, String agentId, String model, String toolModel,
                             Boolean useRag, String ragQuery, Integer ragTopK) {
        Session session = getSession(chatId);
        applyToolModelUpdate(session, toolModel);
        String finalModel = resolveModelForSession(session, model);
        validateGptMatch(session, gptId);
        validateAgentMatch(session, agentId);
        String systemPrompt = resolveSystemPrompt(session);
        String ragContext = resolveRagContext(session, userId, useRag, ragQuery, ragTopK, prompt);
        String mergedPrompt = mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = resolveAgentEntity(session);
        boolean multiAgentEnabled = isMultiAgentEnabled(sessionAgent);
        if (multiAgentEnabled) {
            log.info("Multi-agent enabled: managerAgentId={}", sessionAgent != null ? sessionAgent.getAgentId() : null);
        }
        if (multiAgentEnabled) {
            log.info("Multi-agent enabled: managerAgentId={}", sessionAgent != null ? sessionAgent.getAgentId() : null);
        }
        if (multiAgentEnabled) {
            log.info("Multi-agent enabled: managerAgentId={}", sessionAgent != null ? sessionAgent.getAgentId() : null);
        }
        String userMessageId = messageId != null && !messageId.isBlank()
                ? messageId
                : UUID.randomUUID().toString();

        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));

        Message userMessage = Message.builder()
                .messageId(userMessageId)
                .chatId(chatId)
                .parentMessageId(parentMessageId)
                .role("user")
                .content(prompt)
                .model(finalModel)
                .tokens(promptTokens)
                .status("SUCCESS")
                .build();
        baseMapper.insert(userMessage);
        updateSessionTitleIfNeeded(session, prompt, finalModel);

        if (isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = ragNoContextMessageV4();
            String assistantMessageId = UUID.randomUUID().toString();
            Message assistantMessage = Message.builder()
                    .messageId(assistantMessageId)
                    .chatId(chatId)
                    .parentMessageId(userMessageId)
                    .role("assistant")
                    .content(content)
                    .model(finalModel)
                    .tokens(estimateCompletionTokens(content))
                    .status("SUCCESS")
                    .build();
            baseMapper.insert(assistantMessage);
            updateSessionStats(chatId, 2, assistantMessageId);
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            return Flux.just(content);
        }

        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = buildContextMessages(chatId, parentMessageId, prompt, finalModel, mergedPrompt);

        String warning = buildLargeSessionWarning(session);

        AtomicReference<StringBuilder> assistantBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<Boolean> toolCallDetected = new AtomicReference<>(false);
        AtomicReference<Boolean> toolSuspected = new AtomicReference<>(false);
        String assistantMessageId = UUID.randomUUID().toString();

        Flux<String> sourceStream;
        MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = null;
        if (multiAgentEnabled) {
            List<TeamAgentRuntime> teamAgents = resolveTeamAgents(sessionAgent, userId);
            if (multiAgentEnabled) {
                log.info("Resolved team agents: count={}, ids={}",
                        teamAgents.size(),
                        teamAgents.stream().map(a -> a.getAgent() != null ? a.getAgent().getAgentId() : "null").toList());
            }
            if (multiAgentEnabled) {
                log.info("Resolved team agents: count={}, ids={}",
                        teamAgents.size(),
                        teamAgents.stream().map(a -> a.getAgent() != null ? a.getAgent().getAgentId() : "null").toList());
            }
            if (multiAgentEnabled) {
                log.info("Resolved team agents: count={}, ids={}",
                        teamAgents.size(),
                        teamAgents.stream().map(a -> a.getAgent() != null ? a.getAgent().getAgentId() : "null").toList());
            }
            if (!teamAgents.isEmpty()) {
                usageRecorder = (toolModelName, pTokens, cTokens) ->
                        recordToolConsumption(session, assistantMessageId, toolModelName, pTokens, cTokens);
                sourceStream = multiAgentOrchestrator.streamTeam(messages, finalModel, sessionAgent, teamAgents,
                        adapterRegistry, toolExecutor, usageRecorder);
            } else {
                sourceStream = multiAgentOrchestrator.stream(messages, finalModel, adapter);
            }
        } else {
            sourceStream = adapter.streamChat(messages, finalModel);
        }

        Flux<String> stream = Flux.create(sink -> {
            sourceStream.subscribe(
                    chunk -> {
                        assistantBuffer.get().append(chunk);
                        String current = assistantBuffer.get().toString().trim();
                        if (!toolSuspected.get() && current.startsWith("{")) {
                            toolSuspected.set(true);
                        }
                        if (!toolCallDetected.get() && isLikelyToolCall(current)) {
                            toolCallDetected.set(true);
                        }
                        if (!toolSuspected.get() && !toolCallDetected.get()) {
                            sink.next(chunk);
                        }
                    },
                    sink::error,
                    () -> {
                        Mono.fromRunnable(() -> {
                                    String assistantContent = assistantBuffer.get().toString();
                                    ToolHandlingResult handled = handleToolCallIfNeeded(session, messages, assistantContent,
                                            finalModel, assistantMessageId);
                                    String finalContent = handled.content != null ? handled.content : assistantContent;
                                    if (toolSuspected.get() || toolCallDetected.get()) {
                                        if (handled.usedTool) {
                                            sink.next(finalContent);
                                        } else {
                                            sink.next(finalContent);
                                        }
                                    }
                                    int completionTokens = estimateCompletionTokens(finalContent);
                                    Message assistantMessage = Message.builder()
                                            .messageId(assistantMessageId)
                                            .chatId(chatId)
                                            .parentMessageId(userMessageId)
                                            .role("assistant")
                                            .content(finalContent)
                                            .model(finalModel)
                                            .tokens(completionTokens)
                                            .status("SUCCESS")
                                            .build();
                                    baseMapper.insert(assistantMessage);
                                    updateSessionStats(chatId, 2, assistantMessageId);
                                    recordConsumption(userId, chatId, assistantMessageId, finalModel, promptTokens, completionTokens);
                                    eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnError(sink::error)
                                .doOnSuccess(v -> sink.complete())
                                .subscribe();
                    }
            );
        });

        if (warning != null) {
            return Flux.just(warning).concatWith(stream);
        }
        return stream;
    }

    @Override
    @Transactional
    public Flux<String> regenerateAssistant(Long userId, String chatId, String assistantMessageId,
                                            String gptId, String agentId, String model, String toolModel,
                                            Boolean useRag, String ragQuery, Integer ragTopK) {
        Session session = getSession(chatId);
        applyToolModelUpdate(session, toolModel);
        String finalModel = resolveModelForSession(session, model);
        validateGptMatch(session, gptId);
        validateAgentMatch(session, agentId);

        Message originalAssistant = lambdaQuery()
                .eq(Message::getChatId, chatId)
                .eq(Message::getMessageId, assistantMessageId)
                .one();
        if (originalAssistant == null) {
            throw new BusinessException(404, "Assistant message not found");
        }
        if (!"assistant".equals(originalAssistant.getRole())) {
            throw new BusinessException(400, "Target message is not assistant");
        }
        if (originalAssistant.getParentMessageId() == null || originalAssistant.getParentMessageId().isBlank()) {
            throw new BusinessException(400, "Assistant parent user message missing");
        }

        Message parentUser = lambdaQuery()
                .eq(Message::getChatId, chatId)
                .eq(Message::getMessageId, originalAssistant.getParentMessageId())
                .one();
        if (parentUser == null || !"user".equals(parentUser.getRole())) {
            throw new BusinessException(400, "Parent user message not found");
        }

        String prompt = parentUser.getContent() == null ? "" : parentUser.getContent();
        String parentMessageId = parentUser.getParentMessageId();
        String systemPrompt = resolveSystemPrompt(session);
        String ragContext = resolveRagContext(session, userId, useRag, ragQuery, ragTopK, prompt);
        String mergedPrompt = mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = resolveAgentEntity(session);
        boolean multiAgentEnabled = isMultiAgentEnabled(sessionAgent);

        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));

        if (isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = ragNoContextMessageV4();
            String newAssistantMessageId = UUID.randomUUID().toString();
            Message assistantMessage = Message.builder()
                    .messageId(newAssistantMessageId)
                    .chatId(chatId)
                    .parentMessageId(parentUser.getMessageId())
                    .role("assistant")
                    .content(content)
                    .model(finalModel)
                    .tokens(estimateCompletionTokens(content))
                    .status("SUCCESS")
                    .build();
            baseMapper.insert(assistantMessage);
            updateSessionStats(chatId, 1, newAssistantMessageId);
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            return Flux.just(content);
        }

        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = buildContextMessages(chatId, parentMessageId, prompt, finalModel, mergedPrompt);
        String warning = buildLargeSessionWarning(session);

        AtomicReference<StringBuilder> assistantBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<Boolean> toolCallDetected = new AtomicReference<>(false);
        AtomicReference<Boolean> toolSuspected = new AtomicReference<>(false);
        String newAssistantMessageId = UUID.randomUUID().toString();
        String parentUserMessageId = parentUser.getMessageId();

        Flux<String> sourceStream;
        MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = null;
        if (multiAgentEnabled) {
            List<TeamAgentRuntime> teamAgents = resolveTeamAgents(sessionAgent, userId);
            if (!teamAgents.isEmpty()) {
                usageRecorder = (toolModelName, pTokens, cTokens) ->
                        recordToolConsumption(session, newAssistantMessageId, toolModelName, pTokens, cTokens);
                sourceStream = multiAgentOrchestrator.streamTeam(messages, finalModel, sessionAgent, teamAgents,
                        adapterRegistry, toolExecutor, usageRecorder);
            } else {
                sourceStream = multiAgentOrchestrator.stream(messages, finalModel, adapter);
            }
        } else {
            sourceStream = adapter.streamChat(messages, finalModel);
        }

        Flux<String> stream = Flux.create(sink -> {
            sourceStream.subscribe(
                    chunk -> {
                        assistantBuffer.get().append(chunk);
                        String current = assistantBuffer.get().toString().trim();
                        if (!toolSuspected.get() && current.startsWith("{")) {
                            toolSuspected.set(true);
                        }
                        if (!toolCallDetected.get() && isLikelyToolCall(current)) {
                            toolCallDetected.set(true);
                        }
                        if (!toolSuspected.get() && !toolCallDetected.get()) {
                            sink.next(chunk);
                        }
                    },
                    sink::error,
                    () -> {
                        Mono.fromRunnable(() -> {
                                    String assistantContent = assistantBuffer.get().toString();
                                    ToolHandlingResult handled = handleToolCallIfNeeded(session, messages, assistantContent,
                                            finalModel, newAssistantMessageId);
                                    String finalContent = handled.content != null ? handled.content : assistantContent;
                                    if (toolSuspected.get() || toolCallDetected.get()) {
                                        if (handled.usedTool) {
                                            sink.next(finalContent);
                                        } else {
                                            sink.next(finalContent);
                                        }
                                    }
                                    int completionTokens = estimateCompletionTokens(finalContent);
                                    Message assistantMessage = Message.builder()
                                            .messageId(newAssistantMessageId)
                                            .chatId(chatId)
                                            .parentMessageId(parentUserMessageId)
                                            .role("assistant")
                                            .content(finalContent)
                                            .model(finalModel)
                                            .tokens(completionTokens)
                                            .status("SUCCESS")
                                            .build();
                                    baseMapper.insert(assistantMessage);
                                    updateSessionStats(chatId, 1, newAssistantMessageId);
                                    recordConsumption(userId, chatId, newAssistantMessageId, finalModel, promptTokens, completionTokens);
                                    eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnError(sink::error)
                                .doOnSuccess(v -> sink.complete())
                                .subscribe();
                    }
            );
        });

        if (warning != null) {
            return Flux.just(warning).concatWith(stream);
        }
        return stream;
    }

    @Override
    @Transactional
    public Object sendMessage(Long userId, String chatId, String prompt, String parentMessageId, String messageId,
                              String gptId, String agentId, String model, String toolModel, Boolean useRag, String ragQuery, Integer ragTopK) {
        Session session = getSession(chatId);
        applyToolModelUpdate(session, toolModel);
        String finalModel = resolveModelForSession(session, model);
        validateGptMatch(session, gptId);
        validateAgentMatch(session, agentId);
        String systemPrompt = resolveSystemPrompt(session);
        String ragContext = resolveRagContext(session, userId, useRag, ragQuery, ragTopK, prompt);
        String mergedPrompt = mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = resolveAgentEntity(session);
        boolean multiAgentEnabled = isMultiAgentEnabled(sessionAgent);
        String userMessageId = messageId != null && !messageId.isBlank()
                ? messageId
                : UUID.randomUUID().toString();

        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));

        Message userMessage = Message.builder()
                .messageId(userMessageId)
                .chatId(chatId)
                .parentMessageId(parentMessageId)
                .role("user")
                .content(prompt)
                .model(finalModel)
                .tokens(promptTokens)
                .status("SUCCESS")
                .build();
        baseMapper.insert(userMessage);
        updateSessionTitleIfNeeded(session, prompt, finalModel);

        if (isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = ragNoContextMessageV4();
            Message assistantMessage = Message.builder()
                    .messageId(UUID.randomUUID().toString())
                    .chatId(chatId)
                    .parentMessageId(userMessageId)
                    .role("assistant")
                    .content(content)
                    .model(finalModel)
                    .tokens(estimateCompletionTokens(content))
                    .status("SUCCESS")
                    .build();
            baseMapper.insert(assistantMessage);
            updateSessionStats(chatId, 2, assistantMessage.getMessageId());
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            return content;
        }

        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = buildContextMessages(chatId, parentMessageId, prompt, finalModel, mergedPrompt);
        String warning = buildLargeSessionWarning(session);
        String assistantContent;
        MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = null;
        if (multiAgentEnabled) {
            List<TeamAgentRuntime> teamAgents = resolveTeamAgents(sessionAgent, userId);
            if (!teamAgents.isEmpty()) {
                String assistantMessageId = UUID.randomUUID().toString();
                usageRecorder = (toolModelName, pTokens, cTokens) ->
                        recordToolConsumption(session, assistantMessageId, toolModelName, pTokens, cTokens);
                assistantContent = multiAgentOrchestrator.runTeam(messages, finalModel, sessionAgent, teamAgents,
                        adapterRegistry, toolExecutor, usageRecorder);
                ToolHandlingResult handled = handleToolCallIfNeeded(session, messages, assistantContent, finalModel,
                        assistantMessageId);
                String finalContent = handled.content != null ? handled.content : assistantContent;
                int completionTokens = estimateCompletionTokens(finalContent);

                Message assistantMessage = Message.builder()
                        .messageId(assistantMessageId)
                        .chatId(chatId)
                        .parentMessageId(userMessageId)
                        .role("assistant")
                        .content(finalContent)
                        .model(finalModel)
                        .tokens(completionTokens)
                        .status("SUCCESS")
                        .build();
                baseMapper.insert(assistantMessage);
                updateSessionStats(chatId, 2, assistantMessage.getMessageId());
                recordConsumption(userId, chatId, assistantMessage.getMessageId(), finalModel, promptTokens, completionTokens);
                eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));

                if (warning != null) {
                    return warning + finalContent;
                }
                return finalContent;
            } else {
                assistantContent = multiAgentOrchestrator.run(messages, finalModel, adapter);
            }
        } else {
            assistantContent = adapter.chat(messages, finalModel);
        }
        String assistantMessageId = UUID.randomUUID().toString();
        ToolHandlingResult handled = handleToolCallIfNeeded(session, messages, assistantContent, finalModel,
                assistantMessageId);
        String finalContent = handled.content != null ? handled.content : assistantContent;
        int completionTokens = estimateCompletionTokens(finalContent);

        Message assistantMessage = Message.builder()
                .messageId(assistantMessageId)
                .chatId(chatId)
                .parentMessageId(userMessageId)
                .role("assistant")
                .content(finalContent)
                .model(finalModel)
                .tokens(completionTokens)
                .status("SUCCESS")
                .build();
        baseMapper.insert(assistantMessage);
        updateSessionStats(chatId, 2, assistantMessage.getMessageId());
        recordConsumption(userId, chatId, assistantMessage.getMessageId(), finalModel, promptTokens, completionTokens);
        eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));

        if (warning != null) {
            return warning + finalContent;
        }
        return finalContent;
    }

    @Override
    public Object agetChatResponse(String chatId, String messageId, boolean includeSystem) {
        List<Message> messages = lambdaQuery()
                .eq(Message::getChatId, chatId)
                .orderByAsc(Message::getCreatedAt)
                .list();

        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false));

        Map<String, Object> payload = new HashMap<>();
        payload.put("chatId", chatId);
        payload.put("currentMessageId", session != null ? session.getCurrentMessageId() : null);
        payload.put("gptId", session != null ? session.getGptId() : null);
        payload.put("agentId", session != null ? session.getAgentId() : null);
        payload.put("model", session != null ? session.getModel() : null);
        payload.put("toolModel", session != null ? session.getToolModel() : null);
        payload.put("ragEnabled", session != null ? session.getRagEnabled() : null);
        payload.put("messages", messages);
        return payload;
    }

    private void updateSessionStats(String chatId, int messageDelta, String currentMessageId) {
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false));
        if (session == null) {
            return;
        }

        int currentCount = session.getMessageCount() == null ? 0 : session.getMessageCount();
        session.setMessageCount(currentCount + messageDelta);
        session.setLastActiveTime(LocalDateTime.now());
        if (currentMessageId != null) {
            session.setCurrentMessageId(currentMessageId);
        }
        sessionMapper.updateById(session);
    }

    private String buildTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "New Chat";
        }
        String trimmed = prompt.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    private String buildHeuristicTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "New Chat";
        }
        String cleaned = prompt
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return buildTitle(prompt);
        }
        int cut = cleaned.indexOf('。');
        if (cut < 0) cut = cleaned.indexOf('？');
        if (cut < 0) cut = cleaned.indexOf('?');
        if (cut < 0) cut = cleaned.indexOf('!');
        if (cut < 0) cut = cleaned.indexOf('！');
        String first = cut > 0 ? cleaned.substring(0, cut) : cleaned;
        first = first.trim();
        if (first.length() > 30) {
            first = first.substring(0, 30).trim();
        }
        return first.isBlank() ? buildTitle(prompt) : first;
    }

    private String buildTitleWithLlm(String prompt, String model) {
        String fallbackTitle = buildHeuristicTitle(prompt);
        if (prompt == null || prompt.isBlank()) {
            return fallbackTitle;
        }
        try {
            String titleModel = (model == null || model.isBlank()) ? "deepseek-chat" : model;
            LlmAdapter adapter = adapterRegistry.getAdapter(titleModel);
            List<LlmMessage> titleMessages = List.of(
                    new LlmMessage("system",
                            "Summarize the user's message into a concise chat title (max 12 words). " +
                                    "Use a short topic phrase. Do not copy the sentence verbatim " +
                                    "or repeat long sequences from the input. Return plain title only."),
                    new LlmMessage("user", prompt)
            );
            String generated = adapter.chat(titleMessages, titleModel);
            if (generated == null || generated.isBlank()) {
                return fallbackTitle;
            }
            String normalized = generated
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace("\"", "")
                    .trim();
            if ((normalized.startsWith("'") && normalized.endsWith("'"))
                    || (normalized.startsWith("`") && normalized.endsWith("`"))) {
                normalized = normalized.substring(1, normalized.length() - 1).trim();
            }
            if (isTooSimilarTitle(prompt, normalized)) {
                return fallbackTitle;
            }
            if (normalized.length() > 60) {
                normalized = normalized.substring(0, 60).trim();
            }
            return normalized.isBlank() ? fallbackTitle : normalized;
        } catch (Exception ignored) {
            return fallbackTitle;
        }
    }

    private boolean isTooSimilarTitle(String prompt, String title) {
        if (prompt == null || title == null) {
            return false;
        }
        String p = prompt.replaceAll("\\s+", "").toLowerCase();
        String t = title.replaceAll("\\s+", "").toLowerCase();
        if (t.isBlank() || p.isBlank()) {
            return false;
        }
        if (p.equals(t)) {
            return true;
        }
        if (p.startsWith(t) && t.length() >= Math.min(20, p.length())) {
            return true;
        }
        double ratio = (double) t.length() / (double) p.length();
        return p.contains(t) && ratio > 0.7;
    }

    private void updateSessionTitleIfNeeded(Session session, String prompt, String model) {
        if (session == null) {
            return;
        }
        String title = session.getTitle();
        int count = session.getMessageCount() == null ? 0 : session.getMessageCount();
        if (count > 0) {
            return;
        }
        if (title != null && !title.isBlank()
                && !"New Chat".equalsIgnoreCase(title.trim())
                && !"RAG Chat".equalsIgnoreCase(title.trim())) {
            return;
        }
        String newTitle = buildTitleWithLlm(prompt, model);
        if (newTitle == null || newTitle.isBlank()) {
            return;
        }
        session.setTitle(newTitle);
        sessionMapper.updateById(session);
    }

    private List<LlmMessage> buildContextMessages(String chatId, String parentMessageId, String prompt, String model,
                                                  String systemPrompt) {
        List<Message> allMessages = lambdaQuery()
                .eq(Message::getChatId, chatId)
                .orderByAsc(Message::getCreatedAt)
                .list();

        Map<String, Message> messageMap = new HashMap<>();
        for (Message message : allMessages) {
            messageMap.put(message.getMessageId(), message);
        }

        String anchorId = resolveAnchorMessageId(parentMessageId, allMessages, messageMap);
        List<Message> chain = buildMessageChain(anchorId, messageMap);

        List<LlmMessage> result = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(new LlmMessage("system", systemPrompt));
        }
        for (Message message : chain) {
            if (message.getContent() == null) {
                continue;
            }
            // Never allow historical system messages to override the session system prompt.
            if ("system".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            result.add(new LlmMessage(message.getRole(), message.getContent()));
        }
        result.add(new LlmMessage("user", prompt));

        return trimContext(result, model);
    }

    private String resolveAnchorMessageId(String parentMessageId,
                                          List<Message> allMessages,
                                          Map<String, Message> messageMap) {
        if (parentMessageId != null && !parentMessageId.isBlank() && messageMap.containsKey(parentMessageId)) {
            return parentMessageId;
        }
        if (!allMessages.isEmpty()) {
            return allMessages.get(allMessages.size() - 1).getMessageId();
        }
        return null;
    }

    private List<Message> buildMessageChain(String anchorId, Map<String, Message> messageMap) {
        if (anchorId == null) {
            return List.of();
        }

        List<Message> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String currentId = anchorId;
        while (currentId != null && messageMap.containsKey(currentId) && !visited.contains(currentId)) {
            Message message = messageMap.get(currentId);
            chain.add(message);
            visited.add(currentId);
            currentId = message.getParentMessageId();
        }

        List<Message> ordered = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            ordered.add(chain.get(i));
        }
        return ordered;
    }

    private String buildLargeSessionWarning(Session session) {
        if (session == null) {
            return null;
        }
        int count = session.getMessageCount() == null ? 0 : session.getMessageCount();
        if (contextWarnMessages > 0 && count >= contextWarnMessages) {
            return "\n[Notice] This session has many messages. Consider starting a new chat for best quality.\n";
        }
        return null;
    }

    private List<LlmMessage> trimContext(List<LlmMessage> messages, String model) {
        List<LlmMessage> trimmed = new ArrayList<>(messages);
        boolean hasSystem = !trimmed.isEmpty() && "system".equalsIgnoreCase(trimmed.get(0).getRole());
        int windowSize = resolveMaxMessages(model);
        if (windowSize <= 0) {
            return trimmed;
        }
        int keepFrom = hasSystem ? 1 : 0;
        int historySize = trimmed.size() - keepFrom;
        int keepMax = windowSize; // history window only
        if (historySize <= keepMax) {
            return trimmed;
        }
        int start = keepFrom + (historySize - keepMax);
        List<LlmMessage> windowed = new ArrayList<>();
        if (hasSystem) {
            windowed.add(trimmed.get(0));
        }
        for (int i = start; i < trimmed.size(); i++) {
            windowed.add(trimmed.get(i));
        }
        return windowed;
    }

    private Session getSession(String chatId) {
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false));
        if (session == null) {
            throw new BusinessException(404, "Chat session not found");
        }
        return session;
    }

    private void validateGptMatch(Session session, String gptId) {
        if (gptId == null || gptId.isBlank()) {
            return;
        }
        if (session.getGptId() != null && !session.getGptId().equals(gptId)) {
            throw new BusinessException(400, "GPT does not match session");
        }
    }

    private void validateAgentMatch(Session session, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        if (session.getAgentId() != null && !session.getAgentId().equals(agentId)) {
            throw new BusinessException(400, "Agent does not match session");
        }
    }

    private String resolveSystemPrompt(Session session) {
        if (session.getSystemPrompt() != null && !session.getSystemPrompt().isBlank()) {
            return session.getSystemPrompt();
        }
        if ((session.getGptId() == null || session.getGptId().isBlank())
                && (session.getAgentId() == null || session.getAgentId().isBlank())) {
            return null;
        }
        Gpt gpt = null;
        Agent agent = null;
        if (session.getGptId() != null && !session.getGptId().isBlank()) {
            gpt = gptMapper.selectOne(new LambdaQueryWrapper<Gpt>()
                    .eq(Gpt::getGptId, session.getGptId())
                    .eq(Gpt::getIsDeleted, false));
        }
        if (session.getAgentId() != null && !session.getAgentId().isBlank()) {
            agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getAgentId, session.getAgentId())
                    .eq(Agent::getIsDeleted, false));
        }
        String prompt = buildSystemPrompt(gpt, agent);
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        session.setSystemPrompt(prompt);
        sessionMapper.updateById(session);
        return prompt;
    }

    private Gpt resolveGptForSession(Long userId, String gptId) {
        if (gptId == null || gptId.isBlank()) {
            return null;
        }
        Gpt gpt = gptMapper.selectOne(new LambdaQueryWrapper<Gpt>()
                .eq(Gpt::getGptId, gptId)
                .eq(Gpt::getIsDeleted, false));
        if (gpt == null) {
            throw new BusinessException(404, "GPT not found");
        }
        if (Boolean.TRUE.equals(gpt.getIsPublic())) {
            return gpt;
        }
        if (gpt.getUserId() != null && gpt.getUserId().equals(userId)) {
            return gpt;
        }
        if (isAdmin(userId)) {
            return gpt;
        }
        throw new BusinessException(403, "GPT not accessible");
    }

    private Agent resolveAgentForSession(Long userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, agentId)
                .eq(Agent::getIsDeleted, false));
        if (agent == null) {
            throw new BusinessException(404, "Agent not found");
        }
        if (Boolean.TRUE.equals(agent.getIsPublic())) {
            return agent;
        }
        if (agent.getUserId() != null && agent.getUserId().equals(userId)) {
            return agent;
        }
        if (isAdmin(userId)) {
            return agent;
        }
        throw new BusinessException(403, "Agent not accessible");
    }

    private Agent resolveAgentEntity(Session session) {
        if (session == null || session.getAgentId() == null || session.getAgentId().isBlank()) {
            return null;
        }
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, session.getAgentId())
                .eq(Agent::getIsDeleted, false));
    }

    private List<TeamAgentRuntime> resolveTeamAgents(Agent manager, Long userId) {
        if (manager == null) {
            return List.of();
        }
        try {
            List<TeamAgentConfig> configs = List.of();
            if (manager.getTeamConfig() != null && !manager.getTeamConfig().isBlank()) {
                configs = objectMapper.readValue(manager.getTeamConfig(), new TypeReference<List<TeamAgentConfig>>() {});
            }
            List<String> ids;
            if (configs != null && !configs.isEmpty()) {
                ids = new ArrayList<>();
                for (TeamAgentConfig cfg : configs) {
                    if (cfg != null && cfg.getAgentId() != null) {
                        ids.add(cfg.getAgentId());
                    }
                }
            } else {
                if (manager.getTeamAgentIds() == null || manager.getTeamAgentIds().isBlank()) {
                    return List.of();
                }
                ids = objectMapper.readValue(manager.getTeamAgentIds(), new TypeReference<List<String>>() {});
            }
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Agent> agents = agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getIsDeleted, false)
                    .in(Agent::getAgentId, ids));
            if (agents == null || agents.isEmpty()) {
                return List.of();
            }
            List<TeamAgentRuntime> allowed = new ArrayList<>();
            for (Agent agent : agents) {
                if (agent == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(agent.getIsPublic())) {
                    allowed.add(buildRuntime(agent, configs));
                    continue;
                }
                if (userId != null && userId.equals(agent.getUserId())) {
                    allowed.add(buildRuntime(agent, configs));
                    continue;
                }
                if (isAdmin(userId)) {
                    allowed.add(buildRuntime(agent, configs));
                }
            }
            return allowed;
        } catch (Exception e) {
            return List.of();
        }
    }

    private TeamAgentRuntime buildRuntime(Agent agent, List<TeamAgentConfig> configs) {
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(agent);
        List<String> tools = null;
        if (configs != null) {
            for (TeamAgentConfig cfg : configs) {
                if (cfg != null && cfg.getAgentId() != null && cfg.getAgentId().equals(agent.getAgentId())) {
                    runtime.setRole(cfg.getRole());
                    tools = cfg.getTools();
                    break;
                }
            }
        }
        if (tools == null || tools.isEmpty()) {
            tools = readAgentTools(agent);
        }
        runtime.setTools(tools);
        return runtime;
    }

    private List<String> readAgentTools(Agent agent) {
        if (agent == null || agent.getTools() == null || agent.getTools().isBlank()) {
            return List.of();
        }
        try {
            List<String> tools = objectMapper.readValue(agent.getTools(), new TypeReference<List<String>>() {});
            return tools == null ? List.of() : tools;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isMultiAgentEnabled(Agent agent) {
        return agent != null && Boolean.TRUE.equals(agent.getMultiAgent());
    }

    private void incrementGptUsage(Gpt gpt) {
        if (gpt == null || gpt.getId() == null) {
            return;
        }
        Gpt update = new Gpt();
        update.setId(gpt.getId());
        Integer usage = gpt.getUsageCount() == null ? 0 : gpt.getUsageCount();
        update.setUsageCount(usage + 1);
        gptMapper.updateById(update);
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        com.harmony.backend.common.entity.User user = userMapper.selectById(userId);
        return user != null && user.isAdmin();
    }

    private String resolveModelForNewSession(String model, Gpt gpt, Agent agent) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (gpt != null && gpt.getModel() != null && !gpt.getModel().isBlank()) {
            return gpt.getModel();
        }
        if (agent != null && agent.getModel() != null && !agent.getModel().isBlank()) {
            return agent.getModel();
        }
        return "deepseek-chat";
    }

    private String resolveModelForSession(Session session, String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (session.getModel() != null && !session.getModel().isBlank()) {
            return session.getModel();
        }
        return "deepseek-chat";
    }

    private String buildSystemPrompt(Gpt gpt, Agent agent) {
        if (gpt != null && gpt.getInstructions() != null && !gpt.getInstructions().isBlank()) {
            return gpt.getInstructions();
        }
        if (agent == null) {
            return null;
        }
        String instructions = agent.getInstructions();
        if (instructions == null || instructions.isBlank()) {
            return null;
        }
        String toolsBlock = buildToolsBlock(agent.getTools());
        if (toolsBlock == null) {
            return instructions;
        }
        return instructions + "\n\n" + toolsBlock;
    }

    private String buildToolsBlock(String toolsJson) {
        if (toolsJson == null || toolsJson.isBlank()) {
            return null;
        }
        try {
            List<String> tools = objectMapper.readValue(toolsJson, new TypeReference<List<String>>() {});
            if (tools == null || tools.isEmpty()) {
                return null;
            }
            boolean hasDatetime = tools.stream().anyMatch(t -> "datetime".equalsIgnoreCase(t));
            StringBuilder sb = new StringBuilder();
            sb.append("Available tools (call with JSON ONLY):\n");
            for (String key : tools) {
                AgentToolDefinition def = toolRegistry.get(key);
                if (def != null) {
                    sb.append("- ").append(def.getKey()).append(": ").append(def.getDescription()).append("\n");
                } else {
                    sb.append("- ").append(key).append("\n");
                }
            }
            sb.append("When using a tool, respond ONLY with JSON: {\"tool\":\"<key>\",\"input\":\"...\"}.");
            if (hasDatetime) {
                sb.append(" For current time queries, use tool datetime with timezone (e.g. Asia/Shanghai).");
            }
            sb.append(" Do not use tools that are not listed.");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveRagContext(Session session, Long userId, Boolean useRag, String ragQuery, Integer ragTopK, String prompt) {
        boolean enabled = useRag != null ? useRag : Boolean.TRUE.equals(session != null ? session.getRagEnabled() : null);
        if (!enabled) {
            return "";
        }
        String query = (ragQuery == null || ragQuery.isBlank()) ? prompt : ragQuery;
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            return ragService.buildContext(userId, query, ragTopK);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isRagRequired(Session session, Boolean useRag) {
        boolean sessionEnabled = session != null && Boolean.TRUE.equals(session.getRagEnabled());
        if (!sessionEnabled) {
            return false;
        }
        return useRag == null || Boolean.TRUE.equals(useRag);
    }

    private String ragNoContextMessage() {
        return "根据知识库上下文未检索到相关内容，无法回答该问题。请尝试调整问题或补充资料。";
    }

    private String ragNoContextMessageV2() {
        return "根据知识库上下文未检索到相关内容，无法回答该问题。请尝试调整问题或补充资料。";
    }

    private String ragNoContextMessageV3() {
        return "根据知识库上下文未检索到相关内容，无法回答该问题。请尝试调整问题或补充资料。";
    }

    private String ragNoContextMessageV4() {
        return "No relevant context found in the knowledge base. Please refine your question or add documents.";
    }

    private String mergeSystemPrompt(String basePrompt, String ragContext) {
        if (ragContext == null || ragContext.isBlank()) {
            return basePrompt;
        }
        String prefix = "Knowledge base context:\n" + ragContext
                + "\n\nInstruction: Use the knowledge base context to answer. "
                + "If the context does not contain the answer, say you don't know.";
        if (basePrompt == null || basePrompt.isBlank()) {
            return prefix;
        }
        return basePrompt + "\n\n" + prefix;
    }

        private ToolHandlingResult handleToolCallIfNeeded(Session session,
                                          List<LlmMessage> messages,
                                          String assistantContent,
                                          String model,
                                          String assistantMessageId) {
        if (assistantContent == null || assistantContent.isBlank()) {
            return ToolHandlingResult.none(assistantContent);
        }
        ToolCall toolCall = parseToolCall(assistantContent);
        if (toolCall == null || toolCall.tool == null || toolCall.tool.isBlank()) {
            return maybeRunToolByIntent(session, messages, assistantContent, model, assistantMessageId);
        }
        if (!isToolAllowed(session, toolCall.tool)) {
            return handleDisallowedTool(messages, assistantContent, model, toolCall.tool);
        }
        return executeToolAndFollowup(session, toolCall.tool, toolCall.input, messages, assistantContent, model,
                assistantMessageId);
    }

    private ToolHandlingResult maybeRunToolByIntent(Session session,
                                                   List<LlmMessage> messages,
                                                   String assistantContent,
                                                   String model,
                                                   String assistantMessageId) {
        String userPrompt = extractLastUserPrompt(messages);
        if (userPrompt == null || userPrompt.isBlank()) {
            return ToolHandlingResult.none(assistantContent);
        }
        String intentTool = detectToolIntent(session, userPrompt);
        if (intentTool == null) {
            return ToolHandlingResult.none(assistantContent);
        }
        String input = buildToolInput(intentTool, userPrompt);
        return executeToolAndFollowup(session, intentTool, input, messages, assistantContent, model,
                assistantMessageId);
    }

    private ToolHandlingResult executeToolAndFollowup(Session session,
                                                      String toolKey,
                                                      String input,
                                                      List<LlmMessage> messages,
                                                      String assistantContent,
                                                      String model,
                                                      String assistantMessageId) {
        String userPrompt = extractLastUserPrompt(messages);
        String finalInput = normalizeSearchInput(toolKey, input, userPrompt);
        log.info("Tool requested: toolKey={}, input={}", toolKey, finalInput);
        String toolModel = resolveToolModel(session);
        ToolExecutionResult result = toolExecutor.execute(
                new ToolExecutionRequest(toolKey, finalInput, toolModel));
        if (result == null || !result.isSuccess()) {
            String errorMsg = result != null && result.getError() != null
                    ? result.getError()
                    : "Tool execution failed";
            log.warn("Tool failed: toolKey={}, error={}", toolKey, errorMsg);
            if (isSearchTool(toolKey)) {
                ToolExecutionResult fallback = trySearchFallback(toolKey, finalInput, toolModel);
                if (fallback != null && fallback.isSuccess()) {
                    result = fallback;
                } else {
                    return answerWithoutSearch(messages, assistantContent, model);
                }
            } else {
                return ToolHandlingResult.used("Tool execution failed: " + errorMsg);
            }
        }
        if (isSearchTool(toolKey) && isNoResults(result.getOutput())) {
            ToolExecutionResult fallback = trySearchFallback(toolKey, finalInput, toolModel);
            if (fallback != null && fallback.isSuccess() && !isNoResults(fallback.getOutput())) {
                result = fallback;
            } else {
                return answerWithoutSearch(messages, assistantContent, model);
            }
        }
        // fallback handled in trySearchFallback / search tool itself
        try {
            recordToolConsumption(session, assistantMessageId, result.getModel(),
                    result.getPromptTokens(), result.getCompletionTokens());
        } catch (Exception e) {
            log.warn("Tool billing failed: {}", e.getMessage());
            return ToolHandlingResult.used("Tool execution failed: " + e.getMessage());
        }
        log.info("Tool success: toolKey={}, outputSize={}", toolKey,
                result.getOutput() == null ? 0 : result.getOutput().length());
        List<LlmMessage> followup = new ArrayList<>(messages);
        followup.add(new LlmMessage("assistant", assistantContent));
        followup.add(new LlmMessage("user", "Tool result:\n" + result.getOutput()
                + "\n\nPlease provide the final answer to the user based on this result."));
        try {
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String followupAnswer = adapter.chat(followup, model);
            if (isLikelyToolCall(followupAnswer)) {
                // Some models may still emit a tool-call JSON even after tool result is provided.
                // Force one strict no-tool answer; if still invalid, return plain result fallback.
                String strictAnswer = forcePlainAnswerFromToolResult(messages, assistantContent, model, result.getOutput());
                if (strictAnswer == null || strictAnswer.isBlank() || isLikelyToolCall(strictAnswer)) {
                    return ToolHandlingResult.used(buildSearchFallbackAnswer(result.getOutput()));
                }
                return ToolHandlingResult.used(strictAnswer);
            }
            if (followupAnswer == null || followupAnswer.isBlank()) {
                return ToolHandlingResult.used(buildSearchFallbackAnswer(result.getOutput()));
            }
            return ToolHandlingResult.used(followupAnswer == null ? assistantContent : followupAnswer);
        } catch (Exception e) {
            log.warn("Tool followup failed: toolKey={}, error={}", toolKey, e.getMessage());
            return ToolHandlingResult.used("Tool execution failed: " + e.getMessage());
        }
    }

    private String forcePlainAnswerFromToolResult(List<LlmMessage> messages,
                                                  String assistantContent,
                                                  String model,
                                                  String toolOutput) {
        try {
            List<LlmMessage> strictFollowup = new ArrayList<>(messages);
            strictFollowup.add(new LlmMessage("assistant", assistantContent));
            strictFollowup.add(new LlmMessage("user",
                    "Tool result:\n" + toolOutput
                            + "\n\nReturn the final answer directly in plain text. "
                            + "Do NOT call any tool. Do NOT return JSON."));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            return adapter.chat(strictFollowup, model);
        } catch (Exception e) {
            log.warn("Strict tool followup failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildSearchFallbackAnswer(String output) {
        String safe = output == null || output.isBlank() ? "No usable search result." : output;
        return "Search results:\n" + safe;
    }

    private ToolHandlingResult answerWithoutSearch(List<LlmMessage> messages,
                                                   String assistantContent,
                                                   String model) {
        try {
            String userPrompt = extractLastUserPrompt(messages);
            if (!shouldAnswerWithoutSearch(userPrompt)) {
                return ToolHandlingResult.used("抱歉，搜索结果不可用，且该问题依赖最新信息，暂时无法回答。");
            }
            List<LlmMessage> followup = new ArrayList<>(messages);
            followup.add(new LlmMessage("assistant", assistantContent));
            followup.add(new LlmMessage("user",
                    "Search results are unavailable. Answer the user from general knowledge without citing sources."));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String answer = adapter.chat(followup, model);
            return ToolHandlingResult.used(answer == null ? assistantContent : answer);
        } catch (Exception e) {
            log.warn("Fallback answer failed: {}", e.getMessage());
            return ToolHandlingResult.used("Search results unavailable. Please try again later.");
        }
    }

    private boolean shouldAnswerWithoutSearch(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        String lower = prompt.toLowerCase();
        if (lower.matches(".*\\b20\\d{2}\\b.*")) {
            return false;
        }
        String[] timeSensitive = new String[] {
                "最新", "新闻", "公告", "通知", "招生", "报名", "录取", "招聘",
                "政策", "官网", "发布", "会议", "直播", "报道", "排名", "榜单",
                "价格", "汇率", "股价", "天气", "时间", "今天", "现在", "刚刚",
                "今年", "本周", "本月", "本季度"
        };
        for (String token : timeSensitive) {
            if (prompt.contains(token)) {
                return false;
            }
        }
        String[] timeSensitiveEn = new String[] {
                "latest", "news", "announcement", "notice", "admission", "recruit",
                "policy", "official", "release", "ranking", "price", "exchange rate",
                "stock", "weather", "today", "now", "current"
        };
        for (String token : timeSensitiveEn) {
            if (lower.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private ToolHandlingResult handleDisallowedTool(List<LlmMessage> messages,
                                                    String assistantContent,
                                                    String model,
                                                    String toolKey) {
        try {
            List<LlmMessage> followup = new ArrayList<>(messages);
            followup.add(new LlmMessage("assistant", assistantContent));
            String prompt = "The tool '" + toolKey + "' is not available for this agent. "
                    + "Answer the user directly without calling any tool.";
            followup.add(new LlmMessage("user", prompt));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String answer = adapter.chat(followup, model);
            return ToolHandlingResult.used(answer == null ? assistantContent : answer);
        } catch (Exception e) {
            log.warn("Disallowed tool fallback failed: {}", e.getMessage());
            return ToolHandlingResult.used("Tool '" + toolKey + "' is not available. Please answer without tools.");
        }
    }

    private boolean isSearchTool(String toolKey) {
        if (toolKey == null) {
            return false;
        }
        String key = toolKey.toLowerCase();
        return key.equals("web_search");
    }

    private boolean isNoResults(String output) {
        if (output == null) {
            return true;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return "No results found.".equalsIgnoreCase(trimmed)
                || trimmed.startsWith("Search tool returned");
    }

    private ToolExecutionResult trySearchFallback(String failedTool, String input, String toolModel) {
        List<String> order = List.of("web_search");
        String failed = failedTool == null ? "" : failedTool.toLowerCase();
        for (String key : order) {
            if (key.equals(failed)) {
                continue;
            }
            if (!toolRegistry.isValidKey(key)) {
                continue;
            }
            log.info("Tool fallback: {} -> {}", failedTool, key);
            ToolExecutionResult candidate = toolExecutor.execute(
                    new ToolExecutionRequest(key, input, toolModel));
            if (candidate != null && candidate.isSuccess()
                    && candidate.getOutput() != null && !candidate.getOutput().isBlank()
                    && !"No results found.".equalsIgnoreCase(candidate.getOutput().trim())) {
                return candidate;
            }
        }
        return null;
    }

    private String resolveToolModel(Session session) {
        if (session != null && session.getToolModel() != null && !session.getToolModel().isBlank()) {
            return session.getToolModel().trim();
        }
        return null;
    }

    private void recordToolConsumption(Session session,
                                       String messageId,
                                       String model,
                                       Integer promptTokens,
                                       Integer completionTokens) {
        if (session == null || session.getUserId() == null) {
            return;
        }
        if (model == null || model.isBlank()) {
            return;
        }
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        if (prompt + completion <= 0) {
            return;
        }
        double multiplier = billingProperties.getMultiplier(model);
        int billedTokens = estimateBilledTokens(prompt + completion, multiplier);
        ensureBalance(session.getUserId(), billedTokens);
        recordConsumption(session.getUserId(), session.getChatId(), messageId, model, prompt, completion);
    }

    private void applyToolModelUpdate(Session session, String toolModel) {
        if (session == null) {
            return;
        }
        if (toolModel == null || toolModel.isBlank()) {
            return;
        }
        String trimmed = toolModel.trim();
        if (trimmed.equals(session.getToolModel())) {
            return;
        }
        Session update = new Session();
        update.setId(session.getId());
        update.setToolModel(trimmed);
        sessionMapper.updateById(update);
        session.setToolModel(trimmed);
    }

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String extractLastUserPrompt(List<LlmMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage msg = messages.get(i);
            if (msg != null && "user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    private boolean shouldFallbackSearch(String toolKey, String output) {
        if (!"web_search".equalsIgnoreCase(toolKey)) {
            return false;
        }
        if (output == null) {
            return true;
        }
        String trimmed = output.trim();
        return trimmed.isEmpty() || "No results found.".equalsIgnoreCase(trimmed);
    }

    private String normalizeSearchInput(String toolKey, String input, String userPrompt) {
        if (input == null) {
            return null;
        }
        if (!"web_search".equalsIgnoreCase(toolKey)) {
            return input;
        }
        String prompt = userPrompt == null ? "" : userPrompt;
        boolean userSpecifiedYear = prompt.matches(".*\\b20\\d{2}\\b.*");
        if (userSpecifiedYear) {
            return input;
        }
        String replaced = input.replaceAll("\\b20\\d{2}\\b", String.valueOf(LocalDate.now().getYear()));
        return replaced;
    }

    private String detectToolIntent(Session session, String prompt) {
        if (session == null || prompt == null) {
            return null;
        }
        String p = prompt.trim();
        if (p.isEmpty()) {
            return null;
        }
        boolean allowTime = isToolAllowed(session, "datetime");
        boolean allowWeb = isToolAllowed(session, "web_search");
        String lower = p.toLowerCase();
        if (allowTime && isTimeQuery(p, lower)) {
            return "datetime";
        }
        if (!allowTime && allowWeb && isTimeQuery(p, lower)) {
            return "web_search";
        }
        if (isSearchQuery(p, lower)) {
            if (allowWeb) {
                return "web_search";
            }
        }
        return null;
    }

    private boolean isTimeQuery(String original, String lower) {
        return lower.contains("time")
                || lower.contains("now")
                || original.contains("时间")
                || original.contains("几点")
                || original.contains("北京时间")
                || original.contains("上海")
                || original.contains("北京");
    }

    private boolean isSearchQuery(String original, String lower) {
        return original.contains("搜索")
                || original.contains("查询")
                || original.contains("查找")
                || lower.contains("search")
                || lower.contains("news")
                || original.contains("新闻")
                || original.contains("时事");
    }

    private String buildToolInput(String toolKey, String prompt) {
        if ("datetime".equalsIgnoreCase(toolKey)) {
            if (prompt.contains("上海") || prompt.contains("北京时间") || prompt.contains("北京") || prompt.contains("中国")) {
                return "Asia/Shanghai";
            }
            return "UTC";
        }
        if ("web_search".equalsIgnoreCase(toolKey) && isTimeQuery(prompt, prompt.toLowerCase())) {
            return "北京时间 现在";
        }
        return prompt;
    }
    private boolean isToolAllowed(Session session, String toolKey) {
        if (session == null || toolKey == null || toolKey.isBlank()) {
            return false;
        }
        if (session.getAgentId() == null || session.getAgentId().isBlank()) {
            return false;
        }
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, session.getAgentId())
                .eq(Agent::getIsDeleted, false));
        if (agent == null || agent.getTools() == null || agent.getTools().isBlank()) {
            return false;
        }
        try {
            List<String> tools = objectMapper.readValue(agent.getTools(), new TypeReference<List<String>>() {});
            if (tools == null) {
                return false;
            }
            return tools.stream().anyMatch(t -> toolKey.equalsIgnoreCase(t));
        } catch (Exception e) {
            return false;
        }
    }

    private ToolCall parseToolCall(String content) {
        String trimmed = normalizeToolCallJson(content);
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
            Object toolObj = map.get("tool");
            String tool = toolObj == null ? null : toolObj.toString();
            Object inputObj = map.get("input");
            String input;
            if (inputObj == null) {
                input = "";
            } else if (inputObj instanceof String) {
                input = (String) inputObj;
            } else {
                input = objectMapper.writeValueAsString(inputObj);
            }
            return new ToolCall(tool, input);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeToolCallJson(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLf = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLf > 0 && lastFence > firstLf) {
                String inner = trimmed.substring(firstLf + 1, lastFence).trim();
                return inner;
            }
        }
        return trimmed;
    }

    private boolean isLikelyToolCall(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        return trimmed.contains("\"tool\"") || trimmed.contains("\"input\"");
    }

    private static class ToolCall {
        private final String tool;
        private final String input;

        private ToolCall(String tool, String input) {
            this.tool = tool;
            this.input = input;
        }
    }

    private static class ToolHandlingResult {
        private final String content;
        private final boolean usedTool;

        private ToolHandlingResult(String content, boolean usedTool) {
            this.content = content;
            this.usedTool = usedTool;
        }

        private static ToolHandlingResult none(String content) {
            return new ToolHandlingResult(content, false);
        }

        private static ToolHandlingResult used(String content) {
            return new ToolHandlingResult(content, true);
        }
    }

    private int countChars(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage message : messages) {
            if (message.getContent() != null) {
                total += message.getContent().length();
            }
        }
        return total;
    }

    private int estimatePromptTokens(String prompt) {
        return TokenCounter.estimateMessageTokens("user", prompt);
    }

    private int estimateCompletionTokens(String completion) {
        return TokenCounter.estimateMessageTokens("assistant", completion);
    }

    private void ensureBalance(Long userId, int requiredTokens) {
        com.harmony.backend.common.entity.User user = userMapper.selectById(userId);
        if (user == null || user.getTokenBalance() == null || user.getTokenBalance() < requiredTokens) {
            throw new IllegalStateException("Insufficient token balance");
        }
    }

    private void recordConsumption(Long userId, String chatId, String messageId, String model,
                                   int promptTokens, int completionTokens) {
        TokenConsumption consumption = TokenConsumption.builder()
                .userId(userId)
                .chatId(chatId)
                .messageId(messageId)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .build();
        consumption.calculateTotalTokens();
        double multiplier = billingProperties.getMultiplier(model);
        int billedTokens = estimateBilledTokens(consumption.getTotalTokens(), multiplier);
        tokenConsumptionMapper.insert(consumption);
        userMapper.updateTokenBalance(userId, -billedTokens);
    }

    private int estimateBilledTokens(int totalTokens, double multiplier) {
        if (totalTokens <= 0) {
            return 0;
        }
        return (int) Math.ceil(totalTokens * multiplier);
    }

    private int resolveMaxMessages(String model) {
        BillingProperties.ModelLimit limit = billingProperties.getModelLimit(model);
        if (limit != null && limit.getMaxMessages() != null) {
            return limit.getMaxMessages();
        }
        return contextWindowMessages;
    }

    private int resolveMaxCompletionTokens(String model) {
        BillingProperties.ModelLimit limit = billingProperties.getModelLimit(model);
        if (limit != null && limit.getMaxCompletionTokens() != null) {
            return limit.getMaxCompletionTokens();
        }
        return billingProperties.getMaxCompletionTokens();
    }
}
