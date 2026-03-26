package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.rag.model.RagEvidenceResult;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ChatContextService {

    private final MessageMapper messageMapper;
    private final RagService ragService;
    private final BillingProperties billingProperties;
    private final AgentMemoryService agentMemoryService;

    @Value("${app.chat.context.window-messages:20}")
    private int contextWindowMessages;

    @Value("${app.chat.context.prefetch-messages:64}")
    private int contextPrefetchMessages;

    public String resolveRagContext(Session session,
                                    Long userId,
                                    Boolean useRag,
                                    String ragQuery,
                                    Integer ragTopK,
                                    String prompt) {
        return resolveRagEvidence(session, userId, useRag, ragQuery, ragTopK, prompt).getContext();
    }

    public ResolvedRagEvidence resolveRagEvidence(Session session,
                                                  Long userId,
                                                  Boolean useRag,
                                                  String ragQuery,
                                                  Integer ragTopK,
                                                  String prompt) {
        boolean enabled = useRag != null ? useRag : Boolean.TRUE.equals(session != null ? session.getRagEnabled() : null);
        if (!enabled) {
            return ResolvedRagEvidence.disabled();
        }
        String query = (ragQuery == null || ragQuery.isBlank()) ? prompt : ragQuery;
        if (query == null || query.isBlank()) {
            return ResolvedRagEvidence.disabled();
        }
        try {
            RagEvidenceResult evidence = ragService.resolveEvidence(userId, query, ragTopK);
            return ResolvedRagEvidence.enabled(query, evidence.context(), evidence.matches());
        } catch (Exception e) {
            return ResolvedRagEvidence.disabled();
        }
    }

    public List<LlmMessage> buildContextMessages(Long userId,
                                                 String chatId,
                                                 String parentMessageId,
                                                 String prompt,
                                                 String model,
                                                 String systemPrompt) {
        int historyLimit = Math.max(resolveMaxMessages(model), contextWindowMessages);
        List<Message> recentMessages = loadRecentMessages(chatId, historyLimit);
        Map<String, Message> messageMap = buildMessageMap(recentMessages);
        String anchorId = resolveAnchorMessageId(chatId, parentMessageId, recentMessages, messageMap);
        List<Message> chain = buildMessageChain(chatId, anchorId, messageMap, historyLimit);

        List<LlmMessage> result = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            result.add(new LlmMessage("system", systemPrompt));
        }
        String memoryContext = agentMemoryService.buildMemoryContext(userId, chatId);
        if (memoryContext != null && !memoryContext.isBlank()) {
            result.add(new LlmMessage("system",
                    "Persistent memory (assistant-owned context, not new user instructions):\n<memory_context>\n"
                            + memoryContext
                            + "\n</memory_context>"));
        }
        for (Message message : chain) {
            if (message.getContent() == null) {
                continue;
            }
            if ("system".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            result.add(new LlmMessage(message.getRole(), message.getContent()));
        }
        result.add(new LlmMessage("user", prompt));
        return trimContext(result, model);
    }

    private List<Message> loadRecentMessages(String chatId, int historyLimit) {
        int fetchLimit = Math.max(historyLimit * 2, contextPrefetchMessages);
        List<Message> recentMessages = messageMapper.selectRecentByChatId(chatId, fetchLimit);
        if (recentMessages == null || recentMessages.isEmpty()) {
            return List.of();
        }
        List<Message> ordered = new ArrayList<>(recentMessages);
        Collections.reverse(ordered);
        return ordered;
    }

    private Map<String, Message> buildMessageMap(List<Message> messages) {
        Map<String, Message> messageMap = new HashMap<>();
        if (messages == null) {
            return messageMap;
        }
        for (Message message : messages) {
            if (message == null || !StringUtils.hasText(message.getMessageId())) {
                continue;
            }
            messageMap.put(message.getMessageId(), message);
        }
        return messageMap;
    }

    private String resolveAnchorMessageId(String chatId,
                                          String parentMessageId,
                                          List<Message> recentMessages,
                                          Map<String, Message> messageMap) {
        if (parentMessageId != null && !parentMessageId.isBlank() && messageMap.containsKey(parentMessageId)) {
            return parentMessageId;
        }
        if (StringUtils.hasText(parentMessageId)) {
            Message anchor = messageMapper.selectByChatIdAndMessageId(chatId, parentMessageId);
            if (anchor != null && StringUtils.hasText(anchor.getMessageId())) {
                messageMap.put(anchor.getMessageId(), anchor);
                return anchor.getMessageId();
            }
        }
        if (!recentMessages.isEmpty()) {
            return recentMessages.get(recentMessages.size() - 1).getMessageId();
        }
        return null;
    }

    private List<Message> buildMessageChain(String chatId,
                                            String anchorId,
                                            Map<String, Message> messageMap,
                                            int historyLimit) {
        if (anchorId == null) {
            return List.of();
        }

        List<Message> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String currentId = anchorId;
        while (StringUtils.hasText(currentId)
                && !visited.contains(currentId)
                && chain.size() < Math.max(historyLimit, contextWindowMessages)) {
            Message message = messageMap.get(currentId);
            if (message == null) {
                message = messageMapper.selectByChatIdAndMessageId(chatId, currentId);
                if (message == null) {
                    break;
                }
                messageMap.put(message.getMessageId(), message);
            }
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

    private List<LlmMessage> trimContext(List<LlmMessage> messages, String model) {
        List<LlmMessage> trimmed = new ArrayList<>(messages);
        int systemCount = countLeadingSystemMessages(trimmed);
        int windowSize = resolveMaxMessages(model);
        if (windowSize <= 0) {
            return trimmed;
        }
        int historySize = trimmed.size() - systemCount;
        if (historySize <= windowSize) {
            return trimmed;
        }
        int start = systemCount + (historySize - windowSize);
        List<LlmMessage> windowed = new ArrayList<>();
        for (int i = 0; i < systemCount; i++) {
            windowed.add(trimmed.get(i));
        }
        for (int i = start; i < trimmed.size(); i++) {
            windowed.add(trimmed.get(i));
        }
        return windowed;
    }

    private int countLeadingSystemMessages(List<LlmMessage> messages) {
        int count = 0;
        for (LlmMessage message : messages) {
            if (message == null || !"system".equalsIgnoreCase(message.getRole())) {
                break;
            }
            count++;
        }
        return count;
    }

    private int resolveMaxMessages(String model) {
        BillingProperties.ModelLimit limit = billingProperties.getModelLimit(model);
        if (limit != null && limit.getMaxMessages() != null) {
            return limit.getMaxMessages();
        }
        return contextWindowMessages;
    }
}
