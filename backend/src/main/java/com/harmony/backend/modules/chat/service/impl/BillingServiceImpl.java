package com.harmony.backend.modules.chat.service.impl;

import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.TokenConsumptionMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private final TokenConsumptionMapper tokenConsumptionMapper;
    private final UserMapper userMapper;
    private final BillingProperties billingProperties;

    @Override
    public void ensureBalance(Long userId, int requiredTokens) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getTokenBalance() == null || user.getTokenBalance() < requiredTokens) {
            throw new IllegalStateException("Insufficient token balance");
        }
    }

    @Override
    @Transactional
    public void recordConsumption(Long userId, String chatId, String messageId, String model,
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
        int deducted = userMapper.deductTokenBalanceIfEnough(userId, billedTokens);
        if (deducted <= 0) {
            log.warn("Atomic token deduction failed: userId={}, chatId={}, messageId={}, billedTokens={}",
                    userId, chatId, messageId, billedTokens);
            throw new IllegalStateException("Insufficient token balance");
        }
        tokenConsumptionMapper.insert(consumption);
    }

    @Override
    @Transactional
    public void recordToolConsumption(Session session, String messageId, String model,
                                      Integer promptTokens, Integer completionTokens) {
        if (session == null || session.getUserId() == null || model == null || model.isBlank()) {
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

    @Override
    public int estimateBilledTokens(int totalTokens, double multiplier) {
        if (totalTokens <= 0) {
            return 0;
        }
        return (int) Math.ceil(totalTokens * multiplier);
    }
}
