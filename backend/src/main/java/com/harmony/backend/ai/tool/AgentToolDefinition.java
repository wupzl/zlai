package com.harmony.backend.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolDefinition {
    private String key;
    private String name;
    private String description;
}
