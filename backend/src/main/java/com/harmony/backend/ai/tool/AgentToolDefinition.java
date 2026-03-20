package com.harmony.backend.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolDefinition {
    private String key;
    private String name;
    private String description;
    private Map<String, Object> parametersSchema;
    private boolean executable;

    public AgentToolDefinition(String key, String name, String description) {
        this(key, name, description, Map.of(), false);
    }
}
