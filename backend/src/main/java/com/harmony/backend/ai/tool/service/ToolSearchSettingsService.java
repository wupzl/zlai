package com.harmony.backend.ai.tool.service;

import com.harmony.backend.ai.tool.model.ToolSearchSettings;

public interface ToolSearchSettingsService {
    ToolSearchSettings getSettings();

    ToolSearchSettings updateSettings(ToolSearchSettings settings, Long updatedBy);
}
