package com.harmony.backend.common.service;

import com.harmony.backend.common.model.GlobalRateLimitSettings;

public interface GlobalRateLimitSettingsService {
    GlobalRateLimitSettings getSettings();

    GlobalRateLimitSettings updateSettings(GlobalRateLimitSettings settings, Long updatedBy);
}
