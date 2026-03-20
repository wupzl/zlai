package com.harmony.backend.ai.skill.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class SkillMarkdownImportRequest {
    private String markdownContent;
    private String sourceName;
    private List<String> defaultToolKeys;
    private String defaultExecutionMode;
    private Boolean overwriteExisting;
    private Boolean enabled;
    private Map<String, String> toolAliases;
}
