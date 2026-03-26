package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.runtime.AutonomousAgentExecutionResult;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.AgentRun;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class AgentRuntimeBridgeService {

    private final MultiAgentOrchestrator multiAgentOrchestrator;
    private final AgentRunStateService agentRunStateService;

    public AgentRuntimeBridgeService(MultiAgentOrchestrator multiAgentOrchestrator,
                                     AgentRunStateService agentRunStateService) {
        this.multiAgentOrchestrator = multiAgentOrchestrator;
        this.agentRunStateService = agentRunStateService;
    }

    public String runSync(List<LlmMessage> messages,
                          String model,
                          Agent manager,
                          List<TeamAgentRuntime> teamAgents,
                          LlmAdapterRegistry adapterRegistry,
                          MultiAgentOrchestrator.ToolUsageRecorder usageRecorder,
                          Long userId,
                          String chatId,
                          String assistantMessageId) {
        AutonomousAgentExecutionResult result = multiAgentOrchestrator.executeTeamWorkflow(
                messages, model, manager, teamAgents, adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return toChatContent(result);
    }

    public Flux<String> runStream(List<LlmMessage> messages,
                                  String model,
                                  Agent manager,
                                  List<TeamAgentRuntime> teamAgents,
                                  LlmAdapterRegistry adapterRegistry,
                                  MultiAgentOrchestrator.ToolUsageRecorder usageRecorder,
                                  Long userId,
                                  String chatId,
                                  String assistantMessageId) {
        AutonomousAgentExecutionResult result = multiAgentOrchestrator.executeTeamWorkflow(
                messages, model, manager, teamAgents, adapterRegistry, usageRecorder, userId, chatId, assistantMessageId);
        return Flux.just(toChatContent(result));
    }

    public AutonomousAgentExecutionResult resume(String executionId,
                                                 List<LlmMessage> messages,
                                                 String model,
                                                 Agent manager,
                                                 List<TeamAgentRuntime> teamAgents,
                                                 LlmAdapterRegistry adapterRegistry,
                                                 MultiAgentOrchestrator.ToolUsageRecorder usageRecorder) {
        return multiAgentOrchestrator.resumeTeamWorkflow(executionId, messages, model, manager, teamAgents, adapterRegistry, usageRecorder);
    }

    public void cancel(String executionId) {
        multiAgentOrchestrator.cancelTeamWorkflow(executionId);
    }

    public AgentRun getStatus(String executionId) {
        return agentRunStateService.findByExecutionId(executionId);
    }

    private String toChatContent(AutonomousAgentExecutionResult result) {
        if (result == null) {
            return "";
        }
        if (result.isSuccess()) {
            return result.getOutput() == null ? "" : result.getOutput();
        }
        if (AgentRunStatus.WAITING.equals(result.getStatus())) {
            return "[Agent runtime waiting] " + safe(result.getError()) + "\nexecutionId=" + safe(result.getExecutionId());
        }
        if (AgentRunStatus.CANCELLED.equals(result.getStatus())) {
            return "[Agent runtime cancelled] " + safe(result.getError()) + "\nexecutionId=" + safe(result.getExecutionId());
        }
        return "[Agent runtime failed] " + safe(result.getError()) + "\nexecutionId=" + safe(result.getExecutionId());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
