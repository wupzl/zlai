package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.ExecutableAgentTool;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DateTimeTool implements ExecutableAgentTool {

    private final BasicToolHandler basicToolHandler;

    @Override
    public String getKey() {
        return "datetime";
    }

    @Override
    public String getName() {
        return "DateTime";
    }

    @Override
    public String getDescription() {
        return "Get current date and time for a given locale.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Timezone such as UTC, Asia/Shanghai, or UTC+8"
                        )
                )
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String input = request == null ? null : request.getInput();
        String model = request == null ? null : request.getModel();
        return basicToolHandler.execute(new ToolExecutionRequest(getKey(), input, model));
    }
}
