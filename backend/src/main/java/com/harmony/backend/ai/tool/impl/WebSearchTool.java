package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class WebSearchTool implements AgentTool {
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
}
