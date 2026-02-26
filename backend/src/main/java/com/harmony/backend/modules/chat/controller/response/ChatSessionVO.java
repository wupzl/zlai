package com.harmony.backend.modules.chat.controller.response;

import lombok.Data;

@Data
public class ChatSessionVO {
    private String chatId;
    private String title;
    private String model;
    private String toolModel;
    private Integer messageCount;
    private String lastActiveTime;
    private Boolean ragEnabled;
    private String gptId;
    private String agentId;
}
