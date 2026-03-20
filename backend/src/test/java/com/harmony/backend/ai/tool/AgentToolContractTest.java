package com.harmony.backend.ai.tool;

import com.harmony.backend.ai.tool.impl.CalculatorTool;
import com.harmony.backend.ai.tool.impl.BasicToolHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class AgentToolContractTest {

    @Test
    void agentTool_should_expose_non_null_parameter_schema_by_default() {
        AgentTool tool = new CalculatorTool(mock(BasicToolHandler.class));

        assertNotNull(tool.getParametersSchema());
        assertFalse(tool.getParametersSchema().isEmpty());
    }

    @Test
    void executableAgentTool_should_extend_metadata_contract_with_execution() {
        ExecutableAgentTool tool = new StubExecutableTool();

        assertEquals("stub_tool", tool.getKey());
        assertEquals("Stub Tool", tool.getName());
        assertEquals("Tool result", tool.execute(new ToolExecutionRequest("stub_tool", "input", "model")).getOutput());
        assertEquals(Map.of("type", "object", "properties", Map.of("input", Map.of("type", "string"))),
                tool.getParametersSchema());
    }

    private static final class StubExecutableTool implements ExecutableAgentTool {
        @Override
        public String getKey() {
            return "stub_tool";
        }

        @Override
        public String getName() {
            return "Stub Tool";
        }

        @Override
        public String getDescription() {
            return "Stub executable tool used for contract verification.";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "input", Map.of("type", "string")
                    )
            );
        }

        @Override
        public ToolExecutionResult execute(ToolExecutionRequest request) {
            return ToolExecutionResult.ok("Tool result");
        }
    }
}
