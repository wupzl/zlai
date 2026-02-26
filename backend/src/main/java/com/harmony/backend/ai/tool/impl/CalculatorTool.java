package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTool implements AgentTool {
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
}
