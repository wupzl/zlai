package com.harmony.backend.modules.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChatService extends IService<Message> {
    Mono<String> createNewSessionAndGetChatId(Long userId, String prompt, String model, String toolModel, String gptId,
                                              String agentId, Boolean useRag, String ragQuery, Integer ragTopK);

    Flux<String> chat(Long userId, String chatId, String prompt, String parentMessageId,
                      String messageId, String gptId, String agentId, String model, String toolModel,
                      Boolean useRag, String ragQuery, Integer ragTopK);
    Flux<String> regenerateAssistant(Long userId, String chatId, String assistantMessageId,
                                     String gptId, String agentId, String model, String toolModel,
                                     Boolean useRag, String ragQuery, Integer ragTopK);

    Object sendMessage(Long userId, String chatId, String prompt, String parentMessageId, String messageId,
                       String gptId, String agentId, String model, String toolModel, Boolean useRag, String ragQuery, Integer ragTopK);

    Object agetChatResponse(String chatId, String messageId, boolean includeSystem);
}
