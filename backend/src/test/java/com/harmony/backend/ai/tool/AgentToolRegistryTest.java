package com.harmony.backend.ai.tool;

import com.harmony.backend.ai.tool.impl.BasicToolHandler;
import com.harmony.backend.ai.tool.impl.CalculatorTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolRegistryTest {

    @Test
    void registry_should_preserve_metadata_for_all_tools() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new CalculatorTool(mock(BasicToolHandler.class)), new MetadataOnlyTool()));

        AgentToolDefinition calculator = registry.get("calculator");
        AgentToolDefinition metadataOnly = registry.get("metadata_only");

        assertNotNull(calculator);
        assertEquals("Calculator", calculator.getName());
        assertFalse(calculator.getParametersSchema().isEmpty());
        assertTrue(calculator.isExecutable());

        assertNotNull(metadataOnly);
        assertEquals(Map.of("type", "object"), metadataOnly.getParametersSchema());
        assertFalse(metadataOnly.isExecutable());
        assertTrue(registry.isValidKey("metadata_only"));
    }

    @Test
    void registry_should_expose_executable_tools_without_changing_metadata_lookups() {
        ExecutableAgentTool executableTool = new StubExecutableTool();
        AgentToolRegistry registry = new AgentToolRegistry(List.of(executableTool, new CalculatorTool(mock(BasicToolHandler.class))));

        AgentToolDefinition executableDefinition = registry.get("stub_exec");

        assertNotNull(executableDefinition);
        assertTrue(executableDefinition.isExecutable());
        assertEquals(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))),
                executableDefinition.getParametersSchema());
        assertTrue(registry.isExecutable("stub_exec"));
        assertEquals(executableTool, registry.getExecutable("stub_exec"));
        assertNotNull(registry.getExecutable("calculator"));
        assertEquals(2, registry.listExecutable().size());
    }

    private static final class MetadataOnlyTool implements AgentTool {
        @Override
        public String getKey() {
            return "metadata_only";
        }

        @Override
        public String getName() {
            return "Metadata Only";
        }

        @Override
        public String getDescription() {
            return "Tool metadata without executable contract.";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object");
        }
    }

    private static final class StubExecutableTool implements ExecutableAgentTool {
        @Override
        public String getKey() {
            return "stub_exec";
        }

        @Override
        public String getName() {
            return "Stub Executable";
        }

        @Override
        public String getDescription() {
            return "Executable registry test tool.";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string")
                    )
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return ToolExecutionResult.ok("ok");
        }
    }
}
