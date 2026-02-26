package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.config.RagOcrProperties;
import com.harmony.backend.ai.rag.model.OcrSettings;
import com.harmony.backend.ai.rag.service.OcrSettingsService;
import com.harmony.backend.common.constant.AppConfigKeys;
import com.harmony.backend.common.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OcrSettingsServiceImpl implements OcrSettingsService {

    private final AppConfigService appConfigService;
    private final RagOcrProperties defaults;

    @Override
    public OcrSettings getSettings() {
        String enabled = appConfigService.getValue(AppConfigKeys.RAG_OCR_ENABLED);
        String maxImages = appConfigService.getValue(AppConfigKeys.RAG_OCR_MAX_IMAGES);
        String maxBytes = appConfigService.getValue(AppConfigKeys.RAG_OCR_MAX_IMAGE_BYTES);
        String maxPages = appConfigService.getValue(AppConfigKeys.RAG_OCR_MAX_PDF_PAGES);
        String rateLimit = appConfigService.getValue(AppConfigKeys.RAG_OCR_RATE_LIMIT_PER_DAY);
        String window = appConfigService.getValue(AppConfigKeys.RAG_OCR_RATE_LIMIT_WINDOW_SEC);
        String defaultQuota = appConfigService.getValue(AppConfigKeys.RAG_OCR_DEFAULT_USER_QUOTA);

        return OcrSettings.builder()
                .enabled(parseBoolean(enabled, defaults.isEnabled()))
                .maxImagesPerRequest(parseInt(maxImages, defaults.getMaxImagesPerRequest()))
                .maxImageBytes(parseLong(maxBytes, defaults.getMaxImageBytes()))
                .maxPdfPages(parseInt(maxPages, defaults.getMaxPdfPages()))
                .rateLimitPerDay(parseInt(rateLimit, defaults.getRateLimitPerDay()))
                .rateLimitWindowSeconds(parseInt(window, defaults.getRateLimitWindowSeconds()))
                .defaultUserQuota(parseInt(defaultQuota, defaults.getDefaultUserQuota()))
                .build();
    }

    @Override
    public OcrSettings updateSettings(OcrSettings settings, Long updatedBy) {
        if (settings == null) {
            return getSettings();
        }
        appConfigService.setValue(AppConfigKeys.RAG_OCR_ENABLED,
                String.valueOf(settings.isEnabled()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_MAX_IMAGES,
                String.valueOf(settings.getMaxImagesPerRequest()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_MAX_IMAGE_BYTES,
                String.valueOf(settings.getMaxImageBytes()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_MAX_PDF_PAGES,
                String.valueOf(settings.getMaxPdfPages()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_RATE_LIMIT_PER_DAY,
                String.valueOf(settings.getRateLimitPerDay()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_RATE_LIMIT_WINDOW_SEC,
                String.valueOf(settings.getRateLimitWindowSeconds()), updatedBy);
        appConfigService.setValue(AppConfigKeys.RAG_OCR_DEFAULT_USER_QUOTA,
                String.valueOf(settings.getDefaultUserQuota()), updatedBy);
        return getSettings();
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
        } catch (Exception e) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
