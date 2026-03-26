package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentSynthesisService {

    public String synthesize(List<LlmMessage> contextMessages,
                             String defaultModel,
                             Agent manager,
                             List<TeamAgentRuntime> selectedAgents,
                             List<String> outputs,
                             LlmAdapterRegistry adapterRegistry,
                             String planReason) {
        String managerSystem = manager != null && manager.getInstructions() != null && !manager.getInstructions().isBlank()
                ? manager.getInstructions()
                : "You are Manager Agent. Synthesize the selected specialist outputs into one final answer.";
        String managerModel = manager != null && manager.getModel() != null && !manager.getModel().isBlank() ? manager.getModel() : defaultModel;
        LlmAdapter managerAdapter = adapterRegistry.getAdapter(managerModel);
        List<LlmMessage> aggregatorMessages = new ArrayList<>();
        aggregatorMessages.add(new LlmMessage("system", managerSystem + "\nUse the specialist outputs below as workflow step results. Merge duplicates, resolve contradictions, and answer directly."));
        aggregatorMessages.addAll(contextMessages);
        aggregatorMessages.add(new LlmMessage("assistant", formatTeamOutputs(selectedAgents, outputs, planReason)));
        String result = managerAdapter.chat(aggregatorMessages, managerModel);
        return result == null ? "" : result.trim();
    }

    public String formatTeamOutputs(List<TeamAgentRuntime> agents, List<String> outputs, String planReason) {
        StringBuilder sb = new StringBuilder();
        if (planReason != null && !planReason.isBlank()) {
            sb.append("Workflow planner note: ").append(planReason).append("\n\n");
        }
        int size = Math.min(agents.size(), outputs.size());
        for (int i = 0; i < size; i++) {
            TeamAgentRuntime runtime = agents.get(i);
            Agent agent = runtime != null ? runtime.getAgent() : null;
            String name = agent != null && agent.getName() != null ? agent.getName() : "Agent-" + (i + 1);
            sb.append("Step ").append(i + 1).append(" - ").append(name).append(":\n")
                    .append(outputs.get(i) == null ? "" : outputs.get(i)).append("\n\n");
        }
        return sb.toString().trim();
    }
}
