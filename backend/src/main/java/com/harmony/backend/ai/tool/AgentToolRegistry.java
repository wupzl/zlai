package com.harmony.backend.ai.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {

    private final Map<String, AgentToolDefinition> definitionMap = new HashMap<>();
    private final Map<String, ExecutableAgentTool> executableMap = new HashMap<>();

    public AgentToolRegistry(List<AgentTool> tools) {
        if (tools == null) {
            return;
        }
        for (AgentTool tool : tools) {
            if (tool == null || tool.getKey() == null || tool.getKey().isBlank()) {
                continue;
            }
            boolean executable = tool instanceof ExecutableAgentTool executableTool;
            definitionMap.put(tool.getKey(),
                    new AgentToolDefinition(
                            tool.getKey(),
                            tool.getName(),
                            tool.getDescription(),
                            tool.getParametersSchema(),
                            executable
                    ));
            if (executable) {
                executableMap.put(tool.getKey(), (ExecutableAgentTool) tool);
            }
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

    public ExecutableAgentTool getExecutable(String key) {
        if (key == null) {
            return null;
        }
        return executableMap.get(key);
    }

    public boolean isExecutable(String key) {
        return key != null && executableMap.containsKey(key);
    }

    public List<AgentToolDefinition> listExecutable() {
        return executableMap.keySet().stream()
                .map(definitionMap::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }
}
