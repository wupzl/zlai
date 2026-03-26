package com.harmony.backend.ai.agent.controller.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRunStepVO {
    private Integer stepOrder;
    private String stepKey;
    private String agentId;
    private String status;
    private String inputSummary;
    private String outputSummary;
    private String errorMessage;
    private String artifactsJson;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
