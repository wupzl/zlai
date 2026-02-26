package com.harmony.backend.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionRequest {
    private String toolKey;
    private String input;
    private String model;
}
