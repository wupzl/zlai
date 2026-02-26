package com.harmony.backend.ai.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> definitionMap = new HashMap<>();

    public AgentToolRegistry(List<AgentTool> tools) {
        if (tools == null) {
            return;
        }
        for (AgentTool tool : tools) {
            if (tool == null || tool.getKey() == null || tool.getKey().isBlank()) {
                continue;
            }
            definitionMap.put(tool.getKey(),
                    new AgentToolDefinition(tool.getKey(), tool.getName(), tool.getDescription()));
        }
    }

    public List<AgentToolDefinition> listAll() {
        return new ArrayList<>(definitionMap.values());
    }

    public AgentToolDefinition get(String key) {
        if (key == null) {
            return null;
        }
        return definitionMap.get(key);
    }

    public boolean isValidKey(String key) {
        return key != null && definitionMap.containsKey(key);
    }
}
