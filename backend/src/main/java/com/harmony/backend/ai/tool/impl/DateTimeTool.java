package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class DateTimeTool implements AgentTool {
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
}
