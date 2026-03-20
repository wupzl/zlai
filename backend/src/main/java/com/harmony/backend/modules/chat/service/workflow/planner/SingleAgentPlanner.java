package com.harmony.backend.modules.chat.service.workflow.planner;

import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;

import java.util.List;

public interface SingleAgentPlanner {
    SingleAgentPlan plan(Session session,
                         Agent agent,
                         List<LlmMessage> messages,
                         String model,
                         LlmAdapterRegistry adapterRegistry);
}