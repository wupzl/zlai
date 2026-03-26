package com.harmony.backend.ai.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ExecutableAgentTool;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultSkillExecutorTest {

    private AgentSkillRegistry skillRegistry;
    private AgentToolRegistry toolRegistry;
    private ToolExecutor toolExecutor;
    private DefaultSkillExecutor executor;

    @BeforeEach
    void setUp() {
        skillRegistry = mock(AgentSkillRegistry.class);
        toolRegistry = mock(AgentToolRegistry.class);
        toolExecutor = mock(ToolExecutor.class);
        executor = new DefaultSkillExecutor(skillRegistry, toolRegistry, toolExecutor, new ObjectMapper());
        ReflectionTestUtils.setField(executor, "maxPipelineSteps", 4);
    }

    @Test
    void execute_should_prefer_executable_agent_tool_when_available() {
        AgentSkillDefinition skill = new AgentSkillDefinition("kb_summary", "KB Summary", "Summarize KB content", List.of("summarize"), "single", List.of(), List.of());
        ExecutableAgentTool executableTool = new StubExecutableTool();
        when(skillRegistry.get("kb_summary")).thenReturn(skill);
        when(toolRegistry.getExecutable("summarize")).thenReturn(executableTool);

        SkillExecutionResult result = executor.execute(new SkillExecutionRequest("kb_summary", "input-text", "tool-model", "exec-1", "step-1"));

        assertTrue(result.isSuccess());
        assertEquals("executed:input-text", result.getOutput());
        assertEquals(List.of("summarize"), result.getUsedTools());
        assertEquals("exec-1", result.getMetadata().get("execution_id"));
        verify(toolExecutor, never()).execute(any());
    }

    @Test
    void execute_should_fallback_to_tool_executor_when_no_executable_tool_is_registered() {
        AgentSkillDefinition skill = new AgentSkillDefinition("web_research", "Web Research", "Research with web search", List.of("web_search"), "single", List.of(), List.of());
        when(skillRegistry.get("web_research")).thenReturn(skill);
        when(toolRegistry.getExecutable("web_search")).thenReturn(null);
        when(toolExecutor.execute(new ToolExecutionRequest("web_search", "latest redis", "tool-model", "exec-2", "step-2")))
                .thenReturn(ToolExecutionResult.ok("search-result", "tool-model", 5, 2, List.of("web_search"), Map.of("source", "executor")));

        SkillExecutionResult result = executor.execute(new SkillExecutionRequest("web_research", "latest redis", "tool-model", "exec-2", "step-2"));

        assertTrue(result.isSuccess());
        assertEquals("search-result", result.getOutput());
        assertEquals("exec-2", result.getMetadata().get("execution_id"));
        verify(toolExecutor).execute(new ToolExecutionRequest("web_search", "latest redis", "tool-model", "exec-2", "step-2"));
    }

    private static final class StubExecutableTool implements ExecutableAgentTool {
        @Override
        public String getKey() {
            return "summarize";
        }

        @Override
        public String getName() {
            return "Summarize";
        }

        @Override
        public String getDescription() {
            return "Stub executable summarize tool.";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return ToolExecutionResult.ok("executed:" + request.getInput(), request.getModel(), 8, 3, List.of(request.getToolKey()), Map.of("step_key", request.getStepKey()));
        }
    }
}
