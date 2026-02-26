package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class TranslationTool implements AgentTool {
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
}
