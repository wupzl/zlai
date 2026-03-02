package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.ai.tool.ToolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@Primary
@RequiredArgsConstructor
public class DefaultToolExecutor implements ToolExecutor {

    private final AgentToolRegistry toolRegistry;
    private final List<ToolHandler> toolHandlers;

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getToolKey())) {
            return ToolExecutionResult.fail("Tool key is required");
        }
        String key = request.getToolKey().trim();
        if (!toolRegistry.isValidKey(key)) {
            return ToolExecutionResult.fail("Unknown tool: " + key);
        }
        ToolHandler handler = findHandler(key);
        if (handler == null) {
            return ToolExecutionResult.fail("Tool execution not implemented");
        }
        return handler.execute(request);
    }

    private ToolHandler findHandler(String key) {
        if (toolHandlers == null || toolHandlers.isEmpty()) {
            return null;
        }
        for (ToolHandler handler : toolHandlers) {
            if (handler != null && handler.supports(key)) {
                return handler;
            }
        }
        return null;
    }
}

