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
public class WebSearchTool implements ExecutableAgentTool {

    private final WebSearchToolHandler webSearchToolHandler;

    @Override
    public String getKey() {
        return "web_search";
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Search Wikipedia (stable web source) and return top results. Input: search query string.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Search query"
                        )
                ),
                "required", List.of("input")
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String input = request == null ? null : request.getInput();
        String model = request == null ? null : request.getModel();
        return webSearchToolHandler.execute(new ToolExecutionRequest(getKey(), input, model));
    }
}
