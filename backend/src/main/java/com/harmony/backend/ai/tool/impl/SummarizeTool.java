package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class SummarizeTool implements AgentTool {
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
}
