package com.harmony.backend.modules.chat.service;

import com.harmony.backend.common.entity.Session;

public interface BillingService {
    void ensureBalance(Long userId, int requiredTokens);

    void recordConsumption(Long userId, String chatId, String messageId, String model,
                           int promptTokens, int completionTokens);

    void recordToolConsumption(Session session, String messageId, String model,
                               Integer promptTokens, Integer completionTokens);

    int estimateBilledTokens(int totalTokens, double multiplier);
}
