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
public class TranslationTool implements ExecutableAgentTool {

    private final LlmTextToolHandler llmTextToolHandler;

    @Override
    public String getKey() {
        return "translate";
    }

    @Override
    public String getName() {
        return "Translation";
    }

    @Override
    public String getDescription() {
        return "Translate text. Input supports JSON {\"text\":\"...\",\"target\":\"Chinese\"} or plain text.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Plain text or JSON such as {\"text\":\"hello\",\"target\":\"Chinese\"}"
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
