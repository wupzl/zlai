package com.harmony.backend.modules.chat.service.support;

public interface AgentMemoryService {
    String buildMemoryContext(Long userId, String chatId);

    void updateMemoryAfterTurn(Long userId, String chatId, String userPrompt, String assistantContent, String assistantMessageId);

    void recordSkillExecution(Long userId,
                              String chatId,
                              String skillKey,
                              String skillInput,
                              String skillOutput,
                              String assistantMessageId);

    void recordWorkflowProgress(Long userId,
                                String chatId,
                                String workflowKey,
                                String goal,
                                String currentStep,
                                String status,
                                String artifactsJson,
                                String assistantMessageId);
}