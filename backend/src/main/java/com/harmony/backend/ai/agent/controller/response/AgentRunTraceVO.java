package com.harmony.backend.ai.agent.controller.response;

import lombok.Data;

import java.util.List;

@Data
public class AgentRunTraceVO {
    private AgentRunStatusVO run;
    private List<AgentRunStepVO> steps;
}
