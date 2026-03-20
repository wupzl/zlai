package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.ExecutableAgentTool;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CalculatorTool implements ExecutableAgentTool {

    private final BasicToolHandler basicToolHandler;

    @Override
    public String getKey() {
        return "calculator";
    }

    @Override
    public String getName() {
        return "Calculator";
    }

    @Override
    public String getDescription() {
        return "Basic arithmetic and simple calculations.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Arithmetic expression such as 12*(3+4)/2"
                        )
                ),
                "required", java.util.List.of("input")
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String input = request == null ? null : request.getInput();
        String model = request == null ? null : request.getModel();
        return basicToolHandler.execute(new ToolExecutionRequest(getKey(), input, model));
    }
}
