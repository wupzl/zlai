package com.harmony.backend.ai.agent.controller.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AgentVO {
    private String agentId;
    private String name;
    private String description;
    private String instructions;
    private String model;
    private String toolModel;
    private Long userId;
    private List<String> tools;
    private Boolean multiAgent;
    private List<String> teamAgentIds;
    private List<com.harmony.backend.ai.agent.model.TeamAgentConfig> teamConfigs;
    private Boolean isPublic;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
