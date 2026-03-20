package com.harmony.backend.ai.agent.planner;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;

import java.util.List;

public interface MultiAgentPlanner {
    MultiAgentPlan plan(List<LlmMessage> contextMessages,
                        String defaultModel,
                        Agent manager,
                        List<TeamAgentRuntime> teamAgents,
                        LlmAdapterRegistry adapterRegistry);
}