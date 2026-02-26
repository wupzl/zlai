package com.harmony.backend.common.service.impl;

import com.harmony.backend.common.config.GlobalRateLimitProperties;
import com.harmony.backend.common.constant.AppConfigKeys;
import com.harmony.backend.common.model.GlobalRateLimitSettings;
import com.harmony.backend.common.service.AppConfigService;
import com.harmony.backend.common.service.GlobalRateLimitSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GlobalRateLimitSettingsServiceImpl implements GlobalRateLimitSettingsService {

    private static final long CACHE_TTL_MS = 5000L;

    private final AppConfigService appConfigService;
    private final GlobalRateLimitProperties defaults;

    private final AtomicReference<GlobalRateLimitSettings> cached = new AtomicReference<>();
    private final AtomicLong lastLoaded = new AtomicLong(0);

    @Override
    public GlobalRateLimitSettings getSettings() {
        long now = System.currentTimeMillis();
        GlobalRateLimitSettings current = cached.get();
        if (current != null && now - lastLoaded.get() < CACHE_TTL_MS) {
            return current;
        }
        GlobalRateLimitSettings fresh = loadSettings();
        cached.set(fresh);
        lastLoaded.set(now);
        return fresh;
    }

    @Override
    public GlobalRateLimitSettings updateSettings(GlobalRateLimitSettings settings, Long updatedBy) {
        if (settings == null) {
            return getSettings();
        }
        if (settings.getEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_ENABLED,
                    String.valueOf(settings.getEnabled()), updatedBy);
        }
        if (settings.getAdminBypass() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_ADMIN_BYPASS,
                    String.valueOf(settings.getAdminBypass()), updatedBy);
        }
        if (settings.getWindowSeconds() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WINDOW_SECONDS,
                    String.valueOf(settings.getWindowSeconds()), updatedBy);
        }
        if (settings.getIpLimit() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_IP_LIMIT,
                    String.valueOf(settings.getIpLimit()), updatedBy);
        }
        if (settings.getUserLimit() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_USER_LIMIT,
                    String.valueOf(settings.getUserLimit()), updatedBy);
        }
        if (settings.getWhitelistIps() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WHITELIST_IPS,
                    String.join(",", settings.getWhitelistIps()), updatedBy);
        }
        if (settings.getWhitelistPaths() != null) {
            appConfigService.setValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WHITELIST_PATHS,
                    String.join(",", settings.getWhitelistPaths()), updatedBy);
        }
        GlobalRateLimitSettings refreshed = loadSettings();
        cached.set(refreshed);
        lastLoaded.set(System.currentTimeMillis());
        return refreshed;
    }

    private GlobalRateLimitSettings loadSettings() {
        boolean enabled = parseBoolean(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_ENABLED),
                defaults.isEnabled());
        boolean adminBypass = parseBoolean(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_ADMIN_BYPASS),
                defaults.isAdminBypass());
        int windowSeconds = parseInt(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WINDOW_SECONDS),
                defaults.getWindowSeconds());
        int ipLimit = parseInt(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_IP_LIMIT),
                defaults.getIpLimit());
        int userLimit = parseInt(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_USER_LIMIT),
                defaults.getUserLimit());
        List<String> whitelistIps = parseList(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WHITELIST_IPS),
                defaults.getWhitelistIps());
        List<String> whitelistPaths = parseList(appConfigService.getValue(AppConfigKeys.RATE_LIMIT_GLOBAL_WHITELIST_PATHS),
                defaults.getWhitelistPaths());

        return GlobalRateLimitSettings.builder()
                .enabled(enabled)
                .adminBypass(adminBypass)
                .windowSeconds(windowSeconds)
                .ipLimit(ipLimit)
                .userLimit(userLimit)
                .whitelistIps(whitelistIps)
                .whitelistPaths(whitelistPaths)
                .build();
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return fallback;
    }

    private int parseInt(String raw, int fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private List<String> parseList(String raw, List<String> fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback != null ? fallback : Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }
}
