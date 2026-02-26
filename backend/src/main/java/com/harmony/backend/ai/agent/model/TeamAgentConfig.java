package com.harmony.backend.ai.agent.model;

import lombok.Data;

import java.util.List;

@Data
public class TeamAgentConfig {
    private String agentId;
    private String role;
    private List<String> tools;
}

