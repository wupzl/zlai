package com.harmony.backend.ai.runtime;

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
}
