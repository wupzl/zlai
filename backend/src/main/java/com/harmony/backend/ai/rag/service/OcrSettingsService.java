package com.harmony.backend.ai.rag.service;

import com.harmony.backend.ai.rag.model.OcrSettings;

public interface OcrSettingsService {
    OcrSettings getSettings();
    OcrSettings updateSettings(OcrSettings settings, Long updatedBy);
}
