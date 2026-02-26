package com.harmony.backend.ai.agent.controller.request;

import lombok.Data;

import java.util.List;

@Data
public class AgentUpsertRequest {
    private String name;
    private String description;
    private String instructions;
    private String model;
    private String toolModel;
    private List<String> tools;
    private Boolean requestPublic;
    private Boolean multiAgent;
    private List<String> teamAgentIds;
    private List<com.harmony.backend.ai.agent.model.TeamAgentConfig> teamConfigs;
}
