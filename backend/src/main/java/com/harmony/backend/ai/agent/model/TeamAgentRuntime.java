package com.harmony.backend.ai.agent.model;

import com.harmony.backend.common.entity.Agent;
import lombok.Data;

import java.util.List;

@Data
public class TeamAgentRuntime {
    private Agent agent;
    private String role;
    private List<String> tools;
}

