package com.harmony.backend.modules.chat.controller.request;


import lombok.Data;

@Data
public class ChatRequest {

    private String chatId;
    private String prompt;
    private String messageId;
    private String parentMessageId;
    private String regenerateFromAssistantMessageId;
    private String gptId;
    private String agentId;
    private Boolean useRag;
    private String ragQuery;
    private Integer ragTopK;
    private String model;
    private String toolModel;
}
