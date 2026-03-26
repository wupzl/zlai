package com.harmony.backend.ai.runtime;

import com.harmony.backend.ai.agent.runtime.AgentRunStatus;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionResultViewTest {

    @Test
    void toolExecutionResult_should_expose_runtime_view_contract() {
        AgentExecutionResultView result = ToolExecutionResult.ok("tool-output", "tool-model", 12, 4);

        assertTrue(result.isSuccess());
        assertEquals("tool-output", result.getOutput());
        assertEquals("tool-model", result.getModel());
        assertEquals(12, result.getPromptTokens());
        assertTrue(result.getUsedTools().isEmpty());
    }

    @Test
    void skillExecutionResult_should_expose_runtime_view_contract_with_used_tools() {
        AgentExecutionResultView result = SkillExecutionResult.ok("skill-output", "skill-model", 21, 6, List.of("web_search"));

        assertTrue(result.isSuccess());
        assertEquals("skill-output", result.getOutput());
        assertEquals("skill-model", result.getModel());
        assertEquals(List.of("web_search"), result.getUsedTools());
    }

    @Test
    void autonomousAgentExecutionResult_should_expose_lifecycle_fields() {
        AgentExecutionResultView result = AutonomousAgentExecutionResult.completed(
                "final-answer",
                "deepseek-chat",
                "exec-1",
                AgentRunStatus.COMPLETED,
                3
        );

        assertTrue(result.isSuccess());
        assertEquals("final-answer", result.getOutput());
        assertEquals("exec-1", result.getExecutionId());
        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals(3, result.getStepCount());
    }

    @Test
    void autonomousAgentExecutionResult_should_support_waiting_status() {
        AgentExecutionResultView result = AutonomousAgentExecutionResult.waiting("exec-2", "deepseek-chat", 2, "Step budget exhausted");

        assertEquals("exec-2", result.getExecutionId());
        assertEquals(AgentRunStatus.WAITING, result.getStatus());
        assertEquals(2, result.getStepCount());
        assertEquals("Step budget exhausted", result.getError());
    }
}
