package com.harmony.backend.ai.agent.controller.response;

import lombok.Data;

@Data
public class AgentRunStatusVO {
    private String executionId;
    private Long userId;
    private String chatId;
    private String assistantMessageId;
    private String managerAgentId;
    private String status;
    private String currentStep;
    private String waitReason;
    private String errorMessage;
    private Integer stepCount;
    private Integer toolCallCount;
    private String finalOutput;
}
