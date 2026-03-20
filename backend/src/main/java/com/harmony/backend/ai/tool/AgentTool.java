package com.harmony.backend.ai.tool;

import java.util.Map;

public interface AgentTool {
    String getKey();

    String getName();

    String getDescription();

    default Map<String, Object> getParametersSchema() {
        return Map.of();
    }
}
