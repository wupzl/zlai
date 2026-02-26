package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.ai.rag.model.OcrSettings;
import com.harmony.backend.ai.rag.service.OcrSettingsService;
import com.harmony.backend.ai.tool.model.ToolSearchSettings;
import com.harmony.backend.ai.tool.service.ToolSearchSettingsService;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.constant.AppConfigKeys;
import com.harmony.backend.common.service.AppConfigService;
import com.harmony.backend.common.model.GlobalRateLimitSettings;
import com.harmony.backend.common.service.GlobalRateLimitSettingsService;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.admin.controller.request.GlobalRateLimitSettingsRequest;
import com.harmony.backend.modules.admin.controller.request.OcrSettingsRequest;
import com.harmony.backend.modules.admin.controller.request.OpenAiStreamSettingsRequest;
import com.harmony.backend.modules.admin.controller.request.ToolSearchSettingsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingsController {

    private final ToolSearchSettingsService toolSearchSettingsService;
    private final GlobalRateLimitSettingsService globalRateLimitSettingsService;
    private final OcrSettingsService ocrSettingsService;
    private final AppConfigService appConfigService;

    @GetMapping("/tools-search")
    public ApiResponse<ToolSearchSettings> getToolSearchSettings() {
        return ApiResponse.success(toolSearchSettingsService.getSettings());
    }

    @PutMapping("/tools-search")
    public ApiResponse<ToolSearchSettings> updateToolSearchSettings(@RequestBody ToolSearchSettingsRequest request) {
        ToolSearchSettings current = toolSearchSettingsService.getSettings();
        ToolSearchSettings updated = ToolSearchSettings.builder()
                .searxEnabled(request.getSearxEnabled() != null ? request.getSearxEnabled() : current.isSearxEnabled())
                .searxUrl(request.getSearxUrl() != null ? request.getSearxUrl() : current.getSearxUrl())
                .serpApiKey(request.getSerpApiKey() != null ? request.getSerpApiKey() : current.getSerpApiKey())
                .serpApiEngine(request.getSerpApiEngine() != null ? request.getSerpApiEngine() : current.getSerpApiEngine())
                .wikipediaEnabled(request.getWikipediaEnabled() != null ? request.getWikipediaEnabled() : current.getWikipediaEnabled())
                .baikeEnabled(request.getBaikeEnabled() != null ? request.getBaikeEnabled() : current.getBaikeEnabled())
                .bochaEnabled(request.getBochaEnabled() != null ? request.getBochaEnabled() : current.getBochaEnabled())
                .bochaApiKey(request.getBochaApiKey() != null ? request.getBochaApiKey() : current.getBochaApiKey())
                .bochaEndpoint(request.getBochaEndpoint() != null ? request.getBochaEndpoint() : current.getBochaEndpoint())
                .baiduEnabled(request.getBaiduEnabled() != null ? request.getBaiduEnabled() : current.getBaiduEnabled())
                .wikipediaUserAgent(request.getWikipediaUserAgent() != null ? request.getWikipediaUserAgent() : current.getWikipediaUserAgent())
                .wikipediaProxyEnabled(request.getWikipediaProxyEnabled() != null ? request.getWikipediaProxyEnabled() : current.getWikipediaProxyEnabled())
                .wikipediaProxyUrl(request.getWikipediaProxyUrl() != null ? request.getWikipediaProxyUrl() : current.getWikipediaProxyUrl())
                .build();
        Long adminId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(toolSearchSettingsService.updateSettings(updated, adminId));
    }

    @GetMapping("/rate-limit")
    public ApiResponse<GlobalRateLimitSettings> getRateLimitSettings() {
        return ApiResponse.success(globalRateLimitSettingsService.getSettings());
    }

    @PutMapping("/rate-limit")
    public ApiResponse<GlobalRateLimitSettings> updateRateLimitSettings(@RequestBody GlobalRateLimitSettingsRequest request) {
        GlobalRateLimitSettings current = globalRateLimitSettingsService.getSettings();
        GlobalRateLimitSettings updated = GlobalRateLimitSettings.builder()
                .enabled(request.getEnabled() != null ? request.getEnabled() : current.getEnabled())
                .adminBypass(request.getAdminBypass() != null ? request.getAdminBypass() : current.getAdminBypass())
                .windowSeconds(request.getWindowSeconds() != null ? request.getWindowSeconds() : current.getWindowSeconds())
                .ipLimit(request.getIpLimit() != null ? request.getIpLimit() : current.getIpLimit())
                .userLimit(request.getUserLimit() != null ? request.getUserLimit() : current.getUserLimit())
                .whitelistIps(request.getWhitelistIps() != null ? request.getWhitelistIps() : current.getWhitelistIps())
                .whitelistPaths(request.getWhitelistPaths() != null ? request.getWhitelistPaths() : current.getWhitelistPaths())
                .build();
        Long adminId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(globalRateLimitSettingsService.updateSettings(updated, adminId));
    }

    @GetMapping("/ocr")
    public ApiResponse<OcrSettings> getOcrSettings() {
        return ApiResponse.success(ocrSettingsService.getSettings());
    }

    @PutMapping("/ocr")
    public ApiResponse<OcrSettings> updateOcrSettings(@RequestBody OcrSettingsRequest request) {
        OcrSettings current = ocrSettingsService.getSettings();
        OcrSettings updated = OcrSettings.builder()
                .enabled(request.getEnabled() != null ? request.getEnabled() : current.isEnabled())
                .maxImagesPerRequest(request.getMaxImagesPerRequest() != null
                        ? request.getMaxImagesPerRequest()
                        : current.getMaxImagesPerRequest())
                .maxImageBytes(request.getMaxImageBytes() != null
                        ? request.getMaxImageBytes()
                        : current.getMaxImageBytes())
                .maxPdfPages(request.getMaxPdfPages() != null
                        ? request.getMaxPdfPages()
                        : current.getMaxPdfPages())
                .rateLimitPerDay(request.getRateLimitPerDay() != null
                        ? request.getRateLimitPerDay()
                        : current.getRateLimitPerDay())
                .rateLimitWindowSeconds(request.getRateLimitWindowSeconds() != null
                        ? request.getRateLimitWindowSeconds()
                        : current.getRateLimitWindowSeconds())
                .defaultUserQuota(request.getDefaultUserQuota() != null
                        ? request.getDefaultUserQuota()
                        : current.getDefaultUserQuota())
                .build();
        Long adminId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(ocrSettingsService.updateSettings(updated, adminId));
    }

    @GetMapping("/openai-stream")
    public ApiResponse<OpenAiStreamSettingsRequest> getOpenAiStreamSettings() {
        String raw = appConfigService.getValue(AppConfigKeys.OPENAI_STREAM_ENABLED);
        boolean enabled = raw == null ? false : Boolean.parseBoolean(raw);
        return ApiResponse.success(new OpenAiStreamSettingsRequest(enabled));
    }

    @PutMapping("/openai-stream")
    public ApiResponse<OpenAiStreamSettingsRequest> updateOpenAiStreamSettings(
            @RequestBody OpenAiStreamSettingsRequest request) {
        boolean enabled = request != null && request.getEnabled() != null && request.getEnabled();
        Long adminId = RequestUtils.getCurrentUserId();
        appConfigService.setValue(AppConfigKeys.OPENAI_STREAM_ENABLED, String.valueOf(enabled), adminId);
        return ApiResponse.success(new OpenAiStreamSettingsRequest(enabled));
    }
}
