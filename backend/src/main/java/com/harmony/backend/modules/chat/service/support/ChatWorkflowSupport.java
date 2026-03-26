package com.harmony.backend.modules.chat.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.util.TokenCounter;
import com.harmony.backend.modules.chat.service.orchestration.model.UserMessageResolution;
import com.harmony.backend.modules.chat.support.ChatBloomFilterService;
import com.harmony.backend.modules.chat.support.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatWorkflowSupport {

    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ChatBloomFilterService bloomFilterService;

    public UserMessageResolution resolveOrCreateUserMessage(String chatId,
                                                            String userMessageId,
                                                            String parentMessageId,
                                                            String prompt,
                                                            String model,
                                                            int promptTokens) {
        Message existing = findMessageById(userMessageId);
        if (existing == null && bloomFilterService.mightContainMessage(userMessageId)) {
            existing = findMessageById(userMessageId);
        }
        if (existing != null) {
            bloomFilterService.putMessage(existing.getMessageId());
            if (!safeEquals(existing.getChatId(), chatId) || !"user".equals(existing.getRole())) {
                throw new BusinessException(409, "messageId already exists");
            }
            if (!safeEquals(existing.getParentMessageId(), parentMessageId)
                    || !safeEquals(existing.getContent(), prompt)) {
                throw new BusinessException(409, "messageId conflicts with existing message");
            }
            Message latestAssistant = findLatestAssistantReply(chatId, userMessageId);
            return new UserMessageResolution(existing, true, latestAssistant);
        }

        Message userMessage = Message.builder()
                .messageId(userMessageId)
                .chatId(chatId)
                .parentMessageId(parentMessageId)
                .role("user")
                .content(prompt)
                .model(model)
                .tokens(promptTokens)
                .status(MessageStatus.SUCCESS)
                .build();
        try {
            messageMapper.insert(userMessage);
            bloomFilterService.putMessage(userMessageId);
        } catch (DuplicateKeyException e) {
            Message conflict = findMessageById(userMessageId);
            if (conflict != null) {
                bloomFilterService.putMessage(conflict.getMessageId());
            }
            if (conflict == null || !safeEquals(conflict.getChatId(), chatId) || !"user".equals(conflict.getRole())) {
                throw new BusinessException(409, "messageId already exists");
            }
            if (!safeEquals(conflict.getParentMessageId(), parentMessageId)
                    || !safeEquals(conflict.getContent(), prompt)) {
                throw new BusinessException(409, "messageId conflicts with existing message");
            }
            Message latestAssistant = findLatestAssistantReply(chatId, userMessageId);
            return new UserMessageResolution(conflict, true, latestAssistant);
        }
        return new UserMessageResolution(userMessage, false, null);
    }

    public Message findLatestAssistantReply(String chatId, String userMessageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatId, chatId)
                .eq(Message::getParentMessageId, userMessageId)
                .eq(Message::getRole, "assistant")
                .eq(Message::getStatus, MessageStatus.SUCCESS)
                .orderByDesc(Message::getCreatedAt)
                .last("limit 1"));
    }

    public void updateSessionStats(String chatId, int messageDelta, String currentMessageId) {
        int updated = sessionMapper.incrementMessageCount(chatId, messageDelta, currentMessageId, LocalDateTime.now());
        if (updated <= 0) {
            log.warn("Skip session stats update because session was not found or deleted: chatId={}", chatId);
            return;
        }
    }

    public void createAssistantPlaceholder(String chatId, String parentMessageId, String assistantMessageId, String model) {
        Message assistantMessage = Message.builder()
                .messageId(assistantMessageId)
                .chatId(chatId)
                .parentMessageId(parentMessageId)
                .role("assistant")
                .content("")
                .model(model)
                .tokens(0)
                .status(MessageStatus.PENDING)
                .build();
        messageMapper.insert(assistantMessage);
        bloomFilterService.putMessage(assistantMessageId);
    }

    public void markAssistantStreaming(String assistantMessageId) {
        updateAssistantMessageState(assistantMessageId, MessageStatus.STREAMING, null, null);
    }

    public void markAssistantSucceeded(String assistantMessageId, String content) {
        updateAssistantMessageState(
                assistantMessageId,
                MessageStatus.SUCCESS,
                content,
                estimateCompletionTokens(content)
        );
    }

    public void markAssistantFailed(String assistantMessageId, String content, String errorMessage) {
        String finalContent = trimAssistantTerminalContent(content, errorMessage);
        updateAssistantMessageState(
                assistantMessageId,
                MessageStatus.FAILED,
                finalContent,
                estimateCompletionTokens(finalContent)
        );
    }

    public void markAssistantInterrupted(String assistantMessageId, String content) {
        String finalContent = stripInterruptedMarkers(content);
        updateAssistantMessageState(
                assistantMessageId,
                MessageStatus.INTERRUPTED,
                finalContent,
                estimateCompletionTokens(finalContent)
        );
    }

    public String stripInterruptedMarkers(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
                .replace("\r\n", "\n")
                .replaceAll("\\n\\s*\\[Interrupted by client]\\s*$", "")
                .replaceAll("\\n\\s*\\[Interrupted]\\s*$", "")
                .trim();
    }

    private Message findMessageById(String messageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getMessageId, messageId)
                .last("limit 1"));
    }

    private void updateAssistantMessageState(String assistantMessageId, String status, String content, Integer tokens) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return;
        }
        Message update = new Message();
        update.setMessageId(assistantMessageId);
        update.setStatus(status);
        if (content != null) {
            update.setContent(content);
        }
        if (tokens != null) {
            update.setTokens(tokens);
        }
        messageMapper.update(
                update,
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getMessageId, assistantMessageId)
                        .last("limit 1")
        );
    }

    private String trimAssistantTerminalContent(String content, String suffix) {
        String safeContent = content == null ? "" : content;
        String safeSuffix = suffix == null || suffix.isBlank() ? "" : "\n\n" + suffix.trim();
        String merged = safeContent + safeSuffix;
        if (merged.length() <= 16000) {
            return merged;
        }
        return merged.substring(0, 16000);
    }

    private int estimateCompletionTokens(String completion) {
        return TokenCounter.estimateMessageTokens("assistant", completion);
    }

    private boolean safeEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
