package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.ExecutableAgentTool;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SummarizeTool implements ExecutableAgentTool {

    private final LlmTextToolHandler llmTextToolHandler;

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
        return "Summarize text concisely. Input is the text to summarize.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Text content to summarize"
                        )
                ),
                "required", List.of("input")
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String input = request == null ? null : request.getInput();
        String model = request == null ? null : request.getModel();
        return llmTextToolHandler.execute(new ToolExecutionRequest(getKey(), input, model));
    }
}
