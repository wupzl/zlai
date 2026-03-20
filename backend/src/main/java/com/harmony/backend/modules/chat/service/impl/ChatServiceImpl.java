package com.harmony.backend.modules.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.ChatRequestIdempotency;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.event.ChatMessageEvent;
import com.harmony.backend.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.runtime.MultiAgentOrchestrator;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.common.mapper.TokenConsumptionMapper;
import com.harmony.backend.common.util.TokenCounter;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.mapper.ChatRequestIdempotencyMapper;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.controller.response.GroundedChatResponse;
import com.harmony.backend.modules.chat.prompt.ChatPromptService;
import com.harmony.backend.modules.chat.service.BillingService;
import com.harmony.backend.modules.chat.service.ChatService;
import com.harmony.backend.modules.chat.service.IdempotencyService;
import com.harmony.backend.modules.chat.service.orchestration.ChatRegenerateOrchestrationService;
import com.harmony.backend.modules.chat.service.orchestration.ChatStreamOrchestrationService;
import com.harmony.backend.modules.chat.service.orchestration.ChatSyncOrchestrationService;
import com.harmony.backend.modules.chat.service.orchestration.model.ChatIdempotencyGate;
import com.harmony.backend.modules.chat.service.orchestration.model.PreparedChatStream;
import com.harmony.backend.modules.chat.service.orchestration.model.PreparedRegenerateStream;
import com.harmony.backend.modules.chat.service.orchestration.model.PreparedSyncMessage;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import com.harmony.backend.modules.chat.service.orchestration.model.UserMessageResolution;
import com.harmony.backend.modules.chat.service.workflow.AgentWorkflowService;
import com.harmony.backend.modules.chat.service.workflow.model.WorkflowExecutionResult;
import com.harmony.backend.modules.chat.service.support.AgentMemoryService;
import com.harmony.backend.modules.chat.service.support.ChatContextService;
import com.harmony.backend.modules.chat.service.support.GroundingAssessmentService;
import com.harmony.backend.modules.chat.service.support.GroundingFallbackService;
import com.harmony.backend.modules.chat.service.support.GroundingTraceService;
import com.harmony.backend.modules.chat.service.support.RagCitationService;
import com.harmony.backend.modules.chat.service.support.ChatSessionSupportService;
import com.harmony.backend.modules.chat.service.support.ChatToolSupportService;
import com.harmony.backend.modules.chat.service.support.ChatWorkflowSupport;
import com.harmony.backend.modules.chat.service.support.ToolFollowupService;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.support.ChatBloomFilterService;
import com.harmony.backend.modules.chat.support.IdempotencyStatus;
import com.harmony.backend.modules.chat.support.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper, Message> implements ChatService {

    private final SessionMapper sessionMapper;
    private final LlmAdapterRegistry adapterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenConsumptionMapper tokenConsumptionMapper;
    private final ChatRequestIdempotencyMapper chatRequestIdempotencyMapper;
    private final ObjectMapper objectMapper;
    private final BillingProperties billingProperties;
    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final ChatBloomFilterService bloomFilterService;
    private final ChatPromptService chatPromptService;
    private final BillingService billingService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactionTemplate;
    private final ChatStreamOrchestrationService chatStreamOrchestrationService;
    private final ChatRegenerateOrchestrationService chatRegenerateOrchestrationService;
    private final ChatSyncOrchestrationService chatSyncOrchestrationService;
    private final ChatWorkflowSupport chatWorkflowSupport;
    private final ChatContextService chatContextService;
    private final GroundingAssessmentService groundingAssessmentService;
    private final GroundingFallbackService groundingFallbackService;
    private final GroundingTraceService groundingTraceService;
    private final RagCitationService ragCitationService;
    private final ChatSessionSupportService chatSessionSupportService;
    private final ChatToolSupportService chatToolSupportService;
    private final AgentMemoryService agentMemoryService;
    private final ToolFollowupService toolFollowupService;
    private final AgentWorkflowService agentWorkflowService;
    @Value("${app.chat.context.warn-messages:200}")
    private int contextWarnMessages;

    @Value("${app.chat.idempotency.regenerate-dedup-seconds:3}")
    private int regenerateDedupSeconds;

    @Override
    public Mono<String> createNewSessionAndGetChatId(Long userId, String prompt, String model, String toolModel, String gptId,
                                                     String agentId, Boolean useRag, String ragQuery, Integer ragTopK) {
        String chatId = UUID.randomUUID().toString();
        if (gptId != null && !gptId.isBlank() && agentId != null && !agentId.isBlank()) {
            throw new BusinessException(400, "Cannot bind both GPT and Agent");
        }
        Gpt gpt = chatSessionSupportService.resolveGptForSession(userId, gptId);
        Agent agent = chatSessionSupportService.resolveAgentForSession(userId, agentId);
        String finalModel = chatSessionSupportService.resolveModelForNewSession(model, gpt, agent);
        String title = chatPromptService.generateSessionTitle(prompt);
        String systemPrompt = chatPromptService.buildSystemPrompt(gpt, agent);

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
        chatSessionSupportService.incrementGptUsage(gpt);

        return Mono.just(chatId);
    }

    @Override
    public Flux<String> chat(Long userId, String chatId, String prompt, String parentMessageId,
                             String messageId, String requestId, String gptId, String agentId, String model, String toolModel,
                             Boolean useRag, String ragQuery, Integer ragTopK) {
        String requestHash = hashForIdempotency("CHAT_STREAM", chatId, prompt, parentMessageId, messageId,
                gptId, agentId, model, toolModel, useRag, ragQuery, ragTopK);
        ChatIdempotencyGate idempotency = acquireIdempotency(userId, requestId, "CHAT_STREAM", chatId, messageId, requestHash);
        if (idempotency.getReplayResponse() != null) {
            return Flux.just(idempotency.getReplayResponse());
        }
        try {
        PreparedChatStream prepared = inTransaction(() -> prepareChatStream(
                userId, chatId, prompt, parentMessageId, messageId, gptId, agentId, model, toolModel,
                useRag, ragQuery, ragTopK, idempotency));
            return chatStreamOrchestrationService.execute(prepared, idempotency, new ChatStreamOrchestrationService.ChatStreamCallbacks() {
                @Override
                public Flux<String> createSourceStream(PreparedChatStream preparedState) {
                    MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = null;
                    if (preparedState.isMultiAgentEnabled()) {
                        List<TeamAgentRuntime> teamAgents = chatSessionSupportService.resolveTeamAgents(preparedState.getSessionAgent(), userId);
                        log.info("Resolved team agents: count={}, ids={}",
                                teamAgents.size(),
                                teamAgents.stream().map(a -> a.getAgent() != null ? a.getAgent().getAgentId() : "null").toList());
                        if (!teamAgents.isEmpty()) {
                            usageRecorder = (toolModelName, pTokens, cTokens) ->
                                    recordToolConsumption(preparedState.getSession(), preparedState.getAssistantMessageId(), toolModelName, pTokens, cTokens);
                            return multiAgentOrchestrator.streamTeam(preparedState.getMessages(), preparedState.getFinalModel(),
                                    preparedState.getSessionAgent(), teamAgents, adapterRegistry, usageRecorder,
                                    userId, chatId, preparedState.getAssistantMessageId());
                        }
                        return multiAgentOrchestrator.stream(preparedState.getMessages(), preparedState.getFinalModel(), preparedState.getAdapter());
                    }
                    WorkflowExecutionResult workflowResult = agentWorkflowService.executeStream(userId,
                            preparedState.getSession(),
                            preparedState.getSessionAgent(),
                            preparedState.getMessages(),
                            preparedState.getFinalModel(),
                            preparedState.getAssistantMessageId());
                    if (workflowResult != null && workflowResult.isHandled()) {
                        return Flux.create(sink -> {
                            ChatServiceImpl.this.emitChunked(sink, workflowResult.getContent());
                            sink.complete();
                        });
                    }
                    return preparedState.getAdapter().streamChat(preparedState.getMessages(), preparedState.getFinalModel());
                }

                @Override
                public void onStreamingStart(PreparedChatStream preparedState) {
                    chatWorkflowSupport.markAssistantStreaming(preparedState.getAssistantMessageId());
                }

                @Override
                public String resolveFinalContent(PreparedChatStream preparedState, String assistantContent) {
                    String handled = chatToolSupportService.handleToolCallIfNeeded(preparedState.getSession(), preparedState.getMessages(),
                            assistantContent, preparedState.getFinalModel(), preparedState.getAssistantMessageId());
                    return handled != null ? handled : assistantContent;
                }

                @Override
                public void onSuccess(PreparedChatStream preparedState, ChatIdempotencyGate gate, String finalContent) {
                    inTransaction(() -> {
                        chatWorkflowSupport.markAssistantSucceeded(preparedState.getAssistantMessageId(), finalContent);
                        int completionTokens = estimateCompletionTokens(finalContent);
                        recordConsumption(userId, chatId, preparedState.getAssistantMessageId(), preparedState.getFinalModel(),
                                preparedState.getPromptTokens(), completionTokens);
                        eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
                        rememberSuccessfulTurn(userId, chatId, extractLastUserPrompt(preparedState.getMessages()), finalContent,
                                preparedState.getAssistantMessageId());
                        String output = preparedState.getWarning() != null ? preparedState.getWarning() + finalContent : finalContent;
                        markIdempotencyDone(gate.getRecord(), output, chatId, preparedState.getUserMessageId(), preparedState.getAssistantMessageId());
                        return null;
                    });
                }

                @Override
                public void onFailure(PreparedChatStream preparedState, ChatIdempotencyGate gate, String partialContent, Throwable error) {
                    inTransaction(() -> {
                        chatWorkflowSupport.markAssistantFailed(preparedState.getAssistantMessageId(), partialContent, trimError(error.getMessage()));
                        markIdempotencyFailed(gate.getRecord(), error.getMessage());
                        return null;
                    });
                }

                @Override
                public void onCancel(PreparedChatStream preparedState, ChatIdempotencyGate gate, String partialContent) {
                    inTransaction(() -> {
                        chatWorkflowSupport.markAssistantInterrupted(preparedState.getAssistantMessageId(), partialContent);
                        markIdempotencyInterrupted(gate.getRecord(), "interrupted by client");
                        return null;
                    });
                }

                @Override
                public boolean isLikelyToolCall(String content) {
                    return toolFollowupService.isLikelyToolCall(content);
                }

                @Override
                public void emitChunked(reactor.core.publisher.FluxSink<String> sink, String text) {
                    ChatServiceImpl.this.emitChunked(sink, text);
                }
            });
        } catch (Exception e) {
            markIdempotencyFailed(idempotency.getRecord(), e.getMessage());
            throw e;
        }
    }

    @Override
    public Flux<String> regenerateAssistant(Long userId, String chatId, String assistantMessageId,
                                            String requestId,
                                            String gptId, String agentId, String model, String toolModel,
                                            Boolean useRag, String ragQuery, Integer ragTopK) {
        String requestHash = hashForIdempotency("REGENERATE_STREAM", chatId, assistantMessageId,
                gptId, agentId, model, toolModel, useRag, ragQuery, ragTopK);
        ChatIdempotencyGate idempotency = acquireIdempotency(userId, requestId, "REGENERATE_STREAM",
                chatId, assistantMessageId, requestHash);
        if (idempotency.getReplayResponse() != null) {
            return Flux.just(idempotency.getReplayResponse());
        }
        try {
        PreparedRegenerateStream prepared = inTransaction(() -> prepareRegenerateStream(
                userId, chatId, assistantMessageId, gptId, agentId, model, toolModel,
                useRag, ragQuery, ragTopK, idempotency));
            return chatRegenerateOrchestrationService.execute(prepared, idempotency,
                    new ChatRegenerateOrchestrationService.ChatRegenerateCallbacks() {
                        @Override
                        public Flux<String> createSourceStream(PreparedRegenerateStream preparedState) {
                            MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = null;
                            if (preparedState.isMultiAgentEnabled()) {
                                List<TeamAgentRuntime> teamAgents = chatSessionSupportService.resolveTeamAgents(preparedState.getSessionAgent(), userId);
                                if (!teamAgents.isEmpty()) {
                                    usageRecorder = (toolModelName, pTokens, cTokens) ->
                                            recordToolConsumption(preparedState.getSession(), preparedState.getNewAssistantMessageId(), toolModelName, pTokens, cTokens);
                                    return multiAgentOrchestrator.streamTeam(preparedState.getMessages(), preparedState.getFinalModel(),
                                            preparedState.getSessionAgent(), teamAgents, adapterRegistry, usageRecorder,
                                            userId, chatId, preparedState.getNewAssistantMessageId());
                                }
                                return multiAgentOrchestrator.stream(preparedState.getMessages(), preparedState.getFinalModel(), preparedState.getAdapter());
                            }
                            return preparedState.getAdapter().streamChat(preparedState.getMessages(), preparedState.getFinalModel());
                        }

                        @Override
                        public void onStreamingStart(PreparedRegenerateStream preparedState) {
                            chatWorkflowSupport.markAssistantStreaming(preparedState.getNewAssistantMessageId());
                        }

                        @Override
                        public String resolveFinalContent(PreparedRegenerateStream preparedState, String assistantContent) {
                            String handled = chatToolSupportService.handleToolCallIfNeeded(preparedState.getSession(), preparedState.getMessages(),
                                    assistantContent, preparedState.getFinalModel(), preparedState.getNewAssistantMessageId());
                            return handled != null ? handled : assistantContent;
                        }

                        @Override
                        public void onSuccess(PreparedRegenerateStream preparedState, ChatIdempotencyGate gate, String finalContent) {
                            inTransaction(() -> {
                                chatWorkflowSupport.markAssistantSucceeded(preparedState.getNewAssistantMessageId(), finalContent);
                                int completionTokens = estimateCompletionTokens(finalContent);
                                recordConsumption(userId, chatId, preparedState.getNewAssistantMessageId(), preparedState.getFinalModel(),
                                        preparedState.getPromptTokens(), completionTokens);
                                eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
                                String output = preparedState.getWarning() != null ? preparedState.getWarning() + finalContent : finalContent;
                                markIdempotencyDone(gate.getRecord(), output, chatId, preparedState.getParentUserMessageId(),
                                        preparedState.getNewAssistantMessageId());
                                return null;
                            });
                        }

                        @Override
                        public void onFailure(PreparedRegenerateStream preparedState, ChatIdempotencyGate gate, String partialContent, Throwable error) {
                            inTransaction(() -> {
                                chatWorkflowSupport.markAssistantFailed(preparedState.getNewAssistantMessageId(), partialContent, trimError(error.getMessage()));
                                markIdempotencyFailed(gate.getRecord(), error.getMessage());
                                return null;
                            });
                        }

                        @Override
                        public void onCancel(PreparedRegenerateStream preparedState, ChatIdempotencyGate gate, String partialContent) {
                            inTransaction(() -> {
                                chatWorkflowSupport.markAssistantInterrupted(preparedState.getNewAssistantMessageId(), partialContent);
                                markIdempotencyInterrupted(gate.getRecord(), "interrupted by client");
                                return null;
                            });
                        }

                        @Override
                        public boolean isLikelyToolCall(String content) {
                            return toolFollowupService.isLikelyToolCall(content);
                        }

                        @Override
                        public void emitChunked(reactor.core.publisher.FluxSink<String> sink, String text) {
                            ChatServiceImpl.this.emitChunked(sink, text);
                        }
                    });
        } catch (Exception e) {
            markIdempotencyFailed(idempotency.getRecord(), e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional
    public Object sendMessage(Long userId, String chatId, String prompt, String parentMessageId, String messageId, String requestId,
                              String gptId, String agentId, String model, String toolModel, Boolean useRag, String ragQuery, Integer ragTopK) {
        String requestHash = hashForIdempotency("CHAT_SYNC", chatId, prompt, parentMessageId, messageId,
                gptId, agentId, model, toolModel, useRag, ragQuery, ragTopK);
        ChatIdempotencyGate idempotency = acquireIdempotency(userId, requestId, "CHAT_SYNC", chatId, messageId, requestHash);
        if (idempotency.getReplayResponse() != null) {
            return decodeSyncReplayResponse(idempotency.getReplayResponse());
        }
        PreparedSyncMessage prepared = inTransaction(() -> prepareSyncMessage(
                userId, chatId, prompt, parentMessageId, messageId, gptId, agentId, model, toolModel,
                useRag, ragQuery, ragTopK, idempotency));
        return chatSyncOrchestrationService.execute(prepared, idempotency, new ChatSyncOrchestrationService.ChatSyncCallbacks() {
            @Override
            public Object execute(PreparedSyncMessage preparedState, ChatIdempotencyGate gate) {
                String assistantContent;
                if (preparedState.isMultiAgentEnabled()) {
                    List<TeamAgentRuntime> teamAgents = chatSessionSupportService.resolveTeamAgents(preparedState.getSessionAgent(), userId);
                    if (!teamAgents.isEmpty()) {
                        String assistantMessageId = UUID.randomUUID().toString();
                        inTransaction(() -> {
                            chatWorkflowSupport.createAssistantPlaceholder(chatId, preparedState.getUserMessageId(), assistantMessageId, preparedState.getFinalModel());
                            chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
                            return null;
                        });
                        MultiAgentOrchestrator.ToolUsageRecorder usageRecorder = (toolModelName, pTokens, cTokens) ->
                                recordToolConsumption(preparedState.getSession(), assistantMessageId, toolModelName, pTokens, cTokens);
                        assistantContent = multiAgentOrchestrator.runTeam(preparedState.getMessages(), preparedState.getFinalModel(),
                                preparedState.getSessionAgent(), teamAgents, adapterRegistry, usageRecorder,
                                userId, chatId, assistantMessageId);
                        return finalizeSyncResponse(preparedState, gate, assistantMessageId, assistantContent);
                    }
                    assistantContent = multiAgentOrchestrator.run(preparedState.getMessages(), preparedState.getFinalModel(), preparedState.getAdapter());
                } else {
                    String assistantMessageId = UUID.randomUUID().toString();
                    inTransaction(() -> {
                        chatWorkflowSupport.createAssistantPlaceholder(chatId, preparedState.getUserMessageId(), assistantMessageId, preparedState.getFinalModel());
                        chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
                        return null;
                    });
                    WorkflowExecutionResult workflowResult = agentWorkflowService.executeSync(userId,
                            preparedState.getSession(),
                            preparedState.getSessionAgent(),
                            preparedState.getMessages(),
                            preparedState.getFinalModel(),
                            assistantMessageId);
                    if (workflowResult != null && workflowResult.isHandled()) {
                        return finalizeSyncResponse(preparedState, gate, assistantMessageId, workflowResult.getContent());
                    }
                    assistantContent = preparedState.getAdapter().chat(preparedState.getMessages(), preparedState.getFinalModel());
                    return finalizeSyncResponse(preparedState, gate, assistantMessageId, assistantContent);
                }
                String assistantMessageId = UUID.randomUUID().toString();
                inTransaction(() -> {
                    chatWorkflowSupport.createAssistantPlaceholder(chatId, preparedState.getUserMessageId(), assistantMessageId, preparedState.getFinalModel());
                    chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
                    return null;
                });
                return finalizeSyncResponse(preparedState, gate, assistantMessageId, assistantContent);
            }

            @Override
            public void onFailure(PreparedSyncMessage preparedState, ChatIdempotencyGate gate, Exception error) {
                markIdempotencyFailed(gate.getRecord(), error.getMessage());
            }
        });
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

    private ChatIdempotencyGate acquireIdempotency(Long userId,
                                               String requestId,
                                               String requestType,
                                               String chatId,
                                               String messageId,
                                               String requestHash) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(400, "requestId is required");
        }
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        bloomFilterService.putRequest(userId, requestId);
        try {
            IdempotencyService.IdempotencyTicket ticket = idempotencyService.acquire(
                    userId, requestId, requestType, chatId, messageId, requestHash, regenerateDedupSeconds);
            return new ChatIdempotencyGate(ticket.getRecord(), ticket.getReplayResponse());
        } catch (IllegalStateException e) {
            throw new BusinessException(409, e.getMessage());
        }
    }

    private void markIdempotencyDone(ChatRequestIdempotency record,
                                     String responseContent,
                                     String chatId,
                                     String messageId,
                                     String responseMessageId) {
        idempotencyService.markDone(record, responseContent, chatId, messageId, responseMessageId);
    }

    private void markIdempotencyFailed(ChatRequestIdempotency record, String errorMessage) {
        idempotencyService.markFailed(record, errorMessage);
    }

    private void markIdempotencyInterrupted(ChatRequestIdempotency record, String errorMessage) {
        idempotencyService.markInterrupted(record, errorMessage);
    }

    private String hashForIdempotency(Object... parts) {
        return idempotencyService.hashFor(parts);
    }

    private PreparedChatStream prepareChatStream(Long userId,
                                                 String chatId,
                                                 String prompt,
                                                 String parentMessageId,
                                                 String messageId,
                                                 String gptId,
                                                 String agentId,
                                                 String model,
                                                 String toolModel,
                                                 Boolean useRag,
                                                 String ragQuery,
                                                 Integer ragTopK,
                                                 ChatIdempotencyGate idempotency) {
        Session session = chatSessionSupportService.getSession(chatId);
        chatSessionSupportService.applyToolModelUpdate(session, toolModel);
        String finalModel = chatSessionSupportService.resolveModelForSession(session, model);
        chatSessionSupportService.validateGptMatch(session, gptId);
        chatSessionSupportService.validateAgentMatch(session, agentId);
        String systemPrompt = chatSessionSupportService.resolveSystemPrompt(session);
        ResolvedRagEvidence ragEvidence = chatContextService.resolveRagEvidence(session, userId, useRag, ragQuery, ragTopK, prompt);
        String ragContext = ragEvidence.getContext();
        String mergedPrompt = chatPromptService.mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = chatSessionSupportService.resolveAgentEntity(session);
        boolean multiAgentEnabled = chatSessionSupportService.isMultiAgentEnabled(sessionAgent);
        boolean bufferToolStream = chatToolSupportService.shouldBufferToolStream(sessionAgent);
        String userMessageId = messageId != null && !messageId.isBlank() ? messageId : UUID.randomUUID().toString();
        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));
        UserMessageResolution userResolution = chatWorkflowSupport.resolveOrCreateUserMessage(chatId, userMessageId,
                parentMessageId, prompt, finalModel, promptTokens);
        if (!userResolution.reused()) {
            chatWorkflowSupport.updateSessionStats(chatId, 1, userMessageId);
            updateSessionTitleIfNeeded(session, prompt, finalModel);
        } else if (userResolution.latestAssistant() != null) {
            String replay = userResolution.latestAssistant().getContent() == null ? "" : userResolution.latestAssistant().getContent();
            markIdempotencyDone(idempotency.getRecord(), replay, chatId, userMessageId, userResolution.latestAssistant().getMessageId());
            return PreparedChatStream.immediate(replay);
        }
        if (chatSessionSupportService.isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = chatPromptService.ragNoContextMessage();
            String assistantMessageId = UUID.randomUUID().toString();
            Message assistantMessage = Message.builder()
                    .messageId(assistantMessageId)
                    .chatId(chatId)
                    .parentMessageId(userMessageId)
                    .role("assistant")
                    .content(content)
                    .model(finalModel)
                    .tokens(estimateCompletionTokens(content))
                    .status(MessageStatus.SUCCESS)
                    .build();
            baseMapper.insert(assistantMessage);
            bloomFilterService.putMessage(assistantMessageId);
            chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            markIdempotencyDone(idempotency.getRecord(), content, chatId, userMessageId, assistantMessageId);
            return PreparedChatStream.immediate(content);
        }
        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = chatContextService.buildContextMessages(userId, chatId, parentMessageId, prompt, finalModel, mergedPrompt);
        String warning = buildLargeSessionWarning(session);
        String assistantMessageId = UUID.randomUUID().toString();
        chatWorkflowSupport.createAssistantPlaceholder(chatId, userMessageId, assistantMessageId, finalModel);
        chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
        return PreparedChatStream.ready(session, finalModel, sessionAgent, multiAgentEnabled, bufferToolStream,
                userMessageId, promptTokens, warning, adapter, messages, assistantMessageId);
    }

    private PreparedRegenerateStream prepareRegenerateStream(Long userId,
                                                             String chatId,
                                                             String assistantMessageId,
                                                             String gptId,
                                                             String agentId,
                                                             String model,
                                                             String toolModel,
                                                             Boolean useRag,
                                                             String ragQuery,
                                                             Integer ragTopK,
                                                             ChatIdempotencyGate idempotency) {
        Session session = chatSessionSupportService.getSession(chatId);
        chatSessionSupportService.applyToolModelUpdate(session, toolModel);
        String finalModel = chatSessionSupportService.resolveModelForSession(session, model);
        chatSessionSupportService.validateGptMatch(session, gptId);
        chatSessionSupportService.validateAgentMatch(session, agentId);
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
        String systemPrompt = chatSessionSupportService.resolveSystemPrompt(session);
        ResolvedRagEvidence ragEvidence = chatContextService.resolveRagEvidence(session, userId, useRag, ragQuery, ragTopK, prompt);
        String ragContext = ragEvidence.getContext();
        String mergedPrompt = chatPromptService.mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = chatSessionSupportService.resolveAgentEntity(session);
        boolean multiAgentEnabled = chatSessionSupportService.isMultiAgentEnabled(sessionAgent);
        boolean bufferToolStream = chatToolSupportService.shouldBufferToolStream(sessionAgent);
        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));
        String newAssistantMessageId = UUID.randomUUID().toString();
        chatWorkflowSupport.createAssistantPlaceholder(chatId, parentUser.getMessageId(), newAssistantMessageId, finalModel);
        chatWorkflowSupport.updateSessionStats(chatId, 1, newAssistantMessageId);
        if (chatSessionSupportService.isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = chatPromptService.ragNoContextMessage();
            chatWorkflowSupport.markAssistantSucceeded(newAssistantMessageId, content);
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            markIdempotencyDone(idempotency.getRecord(), content, chatId, parentUser.getMessageId(), newAssistantMessageId);
            return PreparedRegenerateStream.immediate(content);
        }
        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = chatContextService.buildContextMessages(userId, chatId, parentMessageId, prompt, finalModel, mergedPrompt);
        String warning = buildLargeSessionWarning(session);
        return PreparedRegenerateStream.ready(session, finalModel, sessionAgent, multiAgentEnabled, bufferToolStream,
                promptTokens, warning, newAssistantMessageId, parentUser.getMessageId(), adapter, messages);
    }

    private PreparedSyncMessage prepareSyncMessage(Long userId,
                                                   String chatId,
                                                   String prompt,
                                                   String parentMessageId,
                                                   String messageId,
                                                   String gptId,
                                                   String agentId,
                                                   String model,
                                                   String toolModel,
                                                   Boolean useRag,
                                                   String ragQuery,
                                                   Integer ragTopK,
                                                   ChatIdempotencyGate idempotency) {
        Session session = chatSessionSupportService.getSession(chatId);
        chatSessionSupportService.applyToolModelUpdate(session, toolModel);
        String finalModel = chatSessionSupportService.resolveModelForSession(session, model);
        chatSessionSupportService.validateGptMatch(session, gptId);
        chatSessionSupportService.validateAgentMatch(session, agentId);
        String systemPrompt = chatSessionSupportService.resolveSystemPrompt(session);
        ResolvedRagEvidence ragEvidence = chatContextService.resolveRagEvidence(session, userId, useRag, ragQuery, ragTopK, prompt);
        String ragContext = ragEvidence.getContext();
        String mergedPrompt = chatPromptService.mergeSystemPrompt(systemPrompt, ragContext);
        Agent sessionAgent = chatSessionSupportService.resolveAgentEntity(session);
        boolean multiAgentEnabled = chatSessionSupportService.isMultiAgentEnabled(sessionAgent);
        String userMessageId = messageId != null && !messageId.isBlank() ? messageId : UUID.randomUUID().toString();
        int promptTokens = estimatePromptTokens(prompt);
        double multiplier = billingProperties.getMultiplier(finalModel);
        int maxCompletion = resolveMaxCompletionTokens(finalModel);
        ensureBalance(userId, estimateBilledTokens(promptTokens + maxCompletion, multiplier));

        UserMessageResolution userResolution = chatWorkflowSupport.resolveOrCreateUserMessage(chatId, userMessageId,
                parentMessageId, prompt, finalModel, promptTokens);
        if (!userResolution.reused()) {
            chatWorkflowSupport.updateSessionStats(chatId, 1, userMessageId);
            updateSessionTitleIfNeeded(session, prompt, finalModel);
        } else if (userResolution.latestAssistant() != null) {
            String replay = userResolution.latestAssistant().getContent() == null ? "" : userResolution.latestAssistant().getContent();
            markIdempotencyDone(idempotency.getRecord(), replay, chatId, userMessageId, userResolution.latestAssistant().getMessageId());
            return PreparedSyncMessage.immediate(replay);
        }
        if (chatSessionSupportService.isRagRequired(session, useRag) && (ragContext == null || ragContext.isBlank())) {
            String content = chatPromptService.ragNoContextMessage();
            String assistantMessageId = UUID.randomUUID().toString();
            chatWorkflowSupport.createAssistantPlaceholder(chatId, userMessageId, assistantMessageId, finalModel);
            chatWorkflowSupport.updateSessionStats(chatId, 1, assistantMessageId);
            chatWorkflowSupport.markAssistantSucceeded(assistantMessageId, content);
            eventPublisher.publishEvent(new ChatMessageEvent(this, chatId));
            markIdempotencyDone(idempotency.getRecord(), content, chatId, userMessageId, assistantMessageId);
            return PreparedSyncMessage.immediate(content);
        }
        LlmAdapter adapter = adapterRegistry.getAdapter(finalModel);
        List<LlmMessage> messages = chatContextService.buildContextMessages(userId, chatId, parentMessageId, prompt, finalModel, mergedPrompt);
        String warning = buildLargeSessionWarning(session);
        return PreparedSyncMessage.ready(session, chatId, finalModel, sessionAgent, multiAgentEnabled,
                userMessageId, promptTokens, warning, adapter, messages, ragEvidence);
    }

    private Object finalizeSyncResponse(PreparedSyncMessage preparedState,
                                        ChatIdempotencyGate gate,
                                        String assistantMessageId,
                                        String assistantContent) {
        String handled = chatToolSupportService.handleToolCallIfNeeded(preparedState.getSession(), preparedState.getMessages(),
                assistantContent, preparedState.getFinalModel(), assistantMessageId);
        String finalContent = handled != null ? handled : assistantContent;
        var derivedCitations = ragCitationService.deriveCitations(finalContent, preparedState.getRagEvidence());
        GroundingAssessment groundingAssessment = null;
        if (preparedState.getRagEvidence() != null && preparedState.getRagEvidence().isEnabled()) {
            groundingAssessment = groundingAssessmentService.assess(finalContent, preparedState.getRagEvidence(), derivedCitations);
        }
        String safeContent = groundingFallbackService.apply(finalContent, groundingAssessment, chatPromptService.ragNoContextMessage());
        List<com.harmony.backend.modules.chat.service.support.model.RagCitation> responseCitations =
                groundingAssessment != null && "insufficient_evidence".equals(groundingAssessment.getStatus())
                        ? List.of()
                        : derivedCitations;
        String userVisibleContent = preparedState.getWarning() != null ? preparedState.getWarning() + safeContent : safeContent;
        var citationDiagnostics = ragCitationService.buildDiagnostics(preparedState.getRagEvidence(), derivedCitations);
        var groundingTrace = groundingTraceService.build(
                preparedState.getChatId(),
                assistantMessageId,
                extractLastUserPrompt(preparedState.getMessages()),
                groundingAssessment,
                citationDiagnostics
        );
        Object responsePayload = buildSyncResponsePayload(userVisibleContent, responseCitations, groundingAssessment);
        if (preparedState.getRagEvidence() != null && preparedState.getRagEvidence().isEnabled()) {
            log.info("RAG grounding diagnostics: chatId={}, assistantMessageId={}, status={}, groundingScore={}, fallbackReason={}, policyVersion={}, downgraded={}, evidenceCount={}, eligibleCitationCount={}, citationCount={}, selectedDocIds={}, selectedTitles={}, citationScores={}, query={}",
                    groundingTrace.getChatId(),
                    groundingTrace.getAssistantMessageId(),
                    groundingTrace.getStatus(),
                    groundingTrace.getGroundingScore(),
                    groundingTrace.getFallbackReason(),
                    groundingTrace.getPolicyVersion(),
                    groundingTrace.isDowngraded(),
                    groundingTrace.getEvidenceCount(),
                    groundingTrace.getEligibleCitationCount(),
                    groundingTrace.getSelectedCitationCount(),
                    groundingTrace.getSelectedDocIds(),
                    groundingTrace.getSelectedTitles(),
                    groundingTrace.getCitationScores(),
                    groundingTrace.getQuery());
        }
        inTransaction(() -> {
            chatWorkflowSupport.markAssistantSucceeded(assistantMessageId, safeContent);
            int completionTokens = estimateCompletionTokens(safeContent);
            recordConsumption(preparedState.getSession().getUserId(), preparedState.getChatId(), assistantMessageId,
                    preparedState.getFinalModel(), preparedState.getPromptTokens(), completionTokens);
            eventPublisher.publishEvent(new ChatMessageEvent(this, preparedState.getChatId()));
            rememberSuccessfulTurn(preparedState.getSession().getUserId(), preparedState.getChatId(),
                    extractLastUserPrompt(preparedState.getMessages()), safeContent, assistantMessageId);
            String output = serializeSyncResponseForIdempotency(responsePayload);
            markIdempotencyDone(gate.getRecord(), output, preparedState.getChatId(),
                    preparedState.getUserMessageId(), assistantMessageId);
            return null;
        });
        return responsePayload;
    }

    private Object buildSyncResponsePayload(String finalContent,
                                            List<com.harmony.backend.modules.chat.service.support.model.RagCitation> citations,
                                            GroundingAssessment groundingAssessment) {
        boolean hasCitations = citations != null && !citations.isEmpty();
        boolean hasGrounding = groundingAssessment != null;
        if (!hasCitations && !hasGrounding) {
            return finalContent;
        }
        return GroundedChatResponse.builder()
                .content(finalContent)
                .citations(hasCitations ? citations : List.of())
                .grounding(groundingAssessment)
                .build();
    }

    private String serializeSyncResponseForIdempotency(Object responsePayload) {
        if (responsePayload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(responsePayload);
        } catch (Exception e) {
            log.warn("Serialize sync response for idempotency failed, fallback to content only", e);
            if (responsePayload instanceof GroundedChatResponse grounded) {
                return grounded.getContent() == null ? "" : grounded.getContent();
            }
            return String.valueOf(responsePayload);
        }
    }

    private Object decodeSyncReplayResponse(String replayResponse) {
        if (replayResponse == null || replayResponse.isBlank()) {
            return replayResponse;
        }
        String trimmed = replayResponse.trim();
        if (!trimmed.startsWith("{")) {
            return replayResponse;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isObject() && root.has("content")) {
                return objectMapper.treeToValue(root, GroundedChatResponse.class);
            }
        } catch (Exception e) {
            log.debug("Replay response is not structured grounded chat payload");
        }
        return replayResponse;
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        return transactionTemplate.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private String trimError(String error) {
        if (error == null || error.isBlank()) {
            return "unknown";
        }
        String trimmed = error.trim();
        return trimmed.length() > 250 ? trimmed.substring(0, 250) : trimmed;
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
        String[] delimiters = new String[] {"\u3002", "\uff01", "\uff1f", ".", "!", "?", ",", "\uff0c", ";", "\uff1b", ":", "\uff1a"};
        int cut = -1;
        for (String delimiter : delimiters) {
            int index = cleaned.indexOf(delimiter);
            if (index >= 0 && (cut < 0 || index < cut)) {
                cut = index;
            }
        }
        String first = cut > 0 ? cleaned.substring(0, cut) : cleaned;
        first = first.trim();
        if (first.length() > 30) {
            first = first.substring(0, 30).trim();
        }
        return first.isBlank() ? buildTitle(prompt) : first;
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
        String newTitle = chatPromptService.generateSessionTitle(prompt);
        if (newTitle == null || newTitle.isBlank()) {
            return;
        }
        session.setTitle(newTitle);
        sessionMapper.updateById(session);
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

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void emitChunked(reactor.core.publisher.FluxSink<String> sink, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        int chunkSize = 120;
        for (int i = 0; i < text.length(); i += chunkSize) {
            sink.next(text.substring(i, Math.min(text.length(), i + chunkSize)));
        }
    }

    private int estimatePromptTokens(String prompt) {
        return TokenCounter.estimateMessageTokens("user", prompt);
    }

    private int estimateCompletionTokens(String completion) {
        return TokenCounter.estimateMessageTokens("assistant", completion);
    }

    private void ensureBalance(Long userId, int requiredTokens) {
        billingService.ensureBalance(userId, requiredTokens);
    }

    private void recordToolConsumption(Session session,
                                       String messageId,
                                       String model,
                                       Integer promptTokens,
                                       Integer completionTokens) {
        billingService.recordToolConsumption(session, messageId, model, promptTokens, completionTokens);
    }

    private void rememberSuccessfulTurn(Long userId,
                                        String chatId,
                                        String userPrompt,
                                        String assistantContent,
                                        String assistantMessageId) {
        agentMemoryService.updateMemoryAfterTurn(userId, chatId, userPrompt, assistantContent, assistantMessageId);
    }

    private String extractLastUserPrompt(List<LlmMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message != null && "user".equalsIgnoreCase(message.getRole())) {
                return message.getContent();
            }
        }
        return null;
    }
    private void recordConsumption(Long userId, String chatId, String messageId, String model,
                                   int promptTokens, int completionTokens) {
        billingService.recordConsumption(userId, chatId, messageId, model, promptTokens, completionTokens);
    }

    private int estimateBilledTokens(int totalTokens, double multiplier) {
        return billingService.estimateBilledTokens(totalTokens, multiplier);
    }

    private int resolveMaxCompletionTokens(String model) {
        BillingProperties.ModelLimit limit = billingProperties.getModelLimit(model);
        if (limit != null && limit.getMaxCompletionTokens() != null) {
            return limit.getMaxCompletionTokens();
        }
        return billingProperties.getMaxCompletionTokens();
    }
}











