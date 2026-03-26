package com.harmony.backend.modules.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.harmony.backend.common.entity.ChatRequestIdempotency;
import com.harmony.backend.common.mapper.ChatRequestIdempotencyMapper;
import com.harmony.backend.modules.chat.service.IdempotencyService;
import com.harmony.backend.modules.chat.support.IdempotencyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {

    private final ChatRequestIdempotencyMapper chatRequestIdempotencyMapper;

    @Override
    public IdempotencyTicket acquire(Long userId, String requestId, String requestType,
                                     String chatId, String messageId, String requestHash, int regenerateDedupSeconds) {
        if (requestId == null || requestId.isBlank()) {
            return new IdempotencyTicket(null, null, false);
        }
        ChatRequestIdempotency record = chatRequestIdempotencyMapper.selectOne(
                new LambdaQueryWrapper<ChatRequestIdempotency>()
                        .eq(ChatRequestIdempotency::getUserId, userId)
                        .eq(ChatRequestIdempotency::getRequestId, requestId)
                        .last("limit 1"));
        if (record == null && "REGENERATE_STREAM".equals(requestType) && regenerateDedupSeconds > 0) {
            IdempotencyTicket merged = tryMergeRecentRegenerate(userId, requestType, requestHash, regenerateDedupSeconds);
            if (merged != null) {
                return merged;
            }
        }
        if (record == null) {
            try {
                ChatRequestIdempotency toInsert = ChatRequestIdempotency.builder()
                        .userId(userId)
                        .requestId(requestId)
                        .requestType(requestType)
                        .chatId(chatId)
                        .messageId(messageId)
                        .requestHash(requestHash)
                        .status(IdempotencyStatus.PENDING)
                        .build();
                chatRequestIdempotencyMapper.insert(toInsert);
                return new IdempotencyTicket(toInsert, null, false);
            } catch (DuplicateKeyException e) {
                record = chatRequestIdempotencyMapper.selectOne(
                        new LambdaQueryWrapper<ChatRequestIdempotency>()
                                .eq(ChatRequestIdempotency::getUserId, userId)
                                .eq(ChatRequestIdempotency::getRequestId, requestId)
                                .last("limit 1"));
            }
        }
        if (record == null) {
            return new IdempotencyTicket(null, null, false);
        }
        if (record.getRequestHash() != null && requestHash != null && !requestHash.equals(record.getRequestHash())) {
            throw new IllegalStateException("requestId already used with different payload");
        }
        if (IdempotencyStatus.DONE.equalsIgnoreCase(record.getStatus())) {
            return new IdempotencyTicket(record, record.getResponseContent() == null ? "" : record.getResponseContent(), false);
        }
        if (IdempotencyStatus.FAILED.equalsIgnoreCase(record.getStatus())
                || IdempotencyStatus.INTERRUPTED.equalsIgnoreCase(record.getStatus())) {
            ChatRequestIdempotency update = new ChatRequestIdempotency();
            update.setId(record.getId());
            update.setStatus(IdempotencyStatus.PENDING);
            update.setErrorMessage(null);
            chatRequestIdempotencyMapper.updateById(update);
            record.setStatus(IdempotencyStatus.PENDING);
            return new IdempotencyTicket(record, null, false);
        }
        return new IdempotencyTicket(record, null, true);
    }

    @Override
    public void markDone(ChatRequestIdempotency record, String responseContent,
                         String chatId, String messageId, String responseMessageId) {
        if (record == null) {
            return;
        }
        ChatRequestIdempotency update = new ChatRequestIdempotency();
        update.setId(record.getId());
        update.setStatus(IdempotencyStatus.DONE);
        update.setResponseContent(responseContent);
        update.setChatId(chatId);
        update.setMessageId(messageId);
        update.setResponseMessageId(responseMessageId);
        update.setErrorMessage(null);
        chatRequestIdempotencyMapper.updateById(update);
    }

    @Override
    public void markFailed(ChatRequestIdempotency record, String errorMessage) {
        if (record == null) {
            return;
        }
        ChatRequestIdempotency update = new ChatRequestIdempotency();
        update.setId(record.getId());
        update.setStatus(IdempotencyStatus.FAILED);
        update.setErrorMessage(trimError(errorMessage));
        chatRequestIdempotencyMapper.updateById(update);
    }

    @Override
    public void markInterrupted(ChatRequestIdempotency record, String errorMessage) {
        if (record == null) {
            return;
        }
        ChatRequestIdempotency update = new ChatRequestIdempotency();
        update.setId(record.getId());
        update.setStatus(IdempotencyStatus.INTERRUPTED);
        update.setErrorMessage(trimError(errorMessage));
        chatRequestIdempotencyMapper.updateById(update);
    }

    @Override
    public String hashFor(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                String text = part == null ? "null" : String.valueOf(part);
                digest.update(text.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Hash idempotency failed", e);
        }
    }

    private IdempotencyTicket tryMergeRecentRegenerate(Long userId, String requestType,
                                                       String requestHash, int regenerateDedupSeconds) {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofSeconds(regenerateDedupSeconds));
        ChatRequestIdempotency latest = chatRequestIdempotencyMapper.selectOne(
                new LambdaQueryWrapper<ChatRequestIdempotency>()
                        .eq(ChatRequestIdempotency::getUserId, userId)
                        .eq(ChatRequestIdempotency::getRequestType, requestType)
                        .eq(ChatRequestIdempotency::getRequestHash, requestHash)
                        .ge(ChatRequestIdempotency::getCreatedAt, cutoff)
                        .orderByDesc(ChatRequestIdempotency::getId)
                        .last("limit 1"));
        if (latest == null) {
            return null;
        }
        if (IdempotencyStatus.DONE.equalsIgnoreCase(latest.getStatus())) {
            return new IdempotencyTicket(latest, latest.getResponseContent() == null ? "" : latest.getResponseContent(), false);
        }
        if (IdempotencyStatus.PENDING.equalsIgnoreCase(latest.getStatus())) {
            return new IdempotencyTicket(latest, null, true);
        }
        return null;
    }

    private String trimError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() > 240 ? errorMessage.substring(0, 240) : errorMessage;
    }
}
