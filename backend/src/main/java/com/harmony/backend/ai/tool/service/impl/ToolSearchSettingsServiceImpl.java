package com.harmony.backend.ai.tool.service.impl;

import com.harmony.backend.ai.tool.model.ToolSearchSettings;
import com.harmony.backend.ai.tool.service.ToolSearchSettingsService;
import com.harmony.backend.common.constant.AppConfigKeys;
import com.harmony.backend.common.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ToolSearchSettingsServiceImpl implements ToolSearchSettingsService {

    private final AppConfigService appConfigService;

    @Value("${app.tools.search.searx-url:}")
    private String defaultSearxUrl;

    @Value("${app.tools.search.searx-enabled:false}")
    private boolean defaultSearxEnabled;

    @Value("${app.tools.search.serpapi-key:}")
    private String defaultSerpApiKey;

    @Value("${app.tools.search.serpapi-engine:baidu}")
    private String defaultSerpApiEngine;

    @Value("${app.tools.search.wikipedia-user-agent:zlAI/1.0 (contact: tomchares0@gmail.com)}")
    private String defaultWikipediaUserAgent;

    @Value("${app.tools.search.wikipedia-enabled:false}")
    private boolean defaultWikipediaEnabled;

    @Value("${app.tools.search.baike-enabled:false}")
    private boolean defaultBaikeEnabled;

    @Value("${app.tools.search.bocha-enabled:true}")
    private boolean defaultBochaEnabled;

    @Value("${app.tools.search.bocha-api-key:}")
    private String defaultBochaApiKey;

    @Value("${app.tools.search.bocha-endpoint:https://api.bocha.cn/v1/web-search}")
    private String defaultBochaEndpoint;

    @Value("${app.tools.search.baidu-enabled:true}")
    private boolean defaultBaiduEnabled;

    @Value("${app.tools.search.wikipedia-proxy-enabled:false}")
    private boolean defaultWikipediaProxyEnabled;

    @Value("${app.tools.search.wikipedia-proxy-url:}")
    private String defaultWikipediaProxyUrl;

    @Override
    public ToolSearchSettings getSettings() {
        String searxEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_SEARX_ENABLED);
        String searxUrl = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_SEARX_URL);
        String serpKey = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_SERPAPI_KEY);
        String serpEngine = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_SERPAPI_ENGINE);
        String wikiEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_ENABLED);
        String baikeEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_BAIKE_ENABLED);
        String bochaEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_ENABLED);
        String bochaApiKey = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_API_KEY);
        String bochaEndpoint = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_ENDPOINT);
        String baiduEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_BAIDU_ENABLED);
        String wikiUa = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_UA);
        String wikiProxyEnabled = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_PROXY_ENABLED);
        String wikiProxyUrl = appConfigService.getValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_PROXY_URL);

        boolean enabled = parseBoolean(searxEnabled, defaultSearxEnabled);
        String resolvedSearxUrl = StringUtils.hasText(searxUrl) ? searxUrl : defaultSearxUrl;
        String resolvedSerpKey = StringUtils.hasText(serpKey) ? serpKey : defaultSerpApiKey;
        String resolvedSerpEngine = StringUtils.hasText(serpEngine) ? serpEngine : defaultSerpApiEngine;
        boolean resolvedWikiEnabled = parseBoolean(wikiEnabled, defaultWikipediaEnabled);
        boolean resolvedBaikeEnabled = parseBoolean(baikeEnabled, defaultBaikeEnabled);
        boolean resolvedBochaEnabled = parseBoolean(bochaEnabled, defaultBochaEnabled);
        String resolvedBochaApiKey = StringUtils.hasText(bochaApiKey) ? bochaApiKey : defaultBochaApiKey;
        String resolvedBochaEndpoint = StringUtils.hasText(bochaEndpoint) ? bochaEndpoint : defaultBochaEndpoint;
        boolean resolvedBaiduEnabled = parseBoolean(baiduEnabled, defaultBaiduEnabled);
        String resolvedWikiUa = StringUtils.hasText(wikiUa) ? wikiUa : defaultWikipediaUserAgent;
        boolean resolvedWikiProxyEnabled = parseBoolean(wikiProxyEnabled, defaultWikipediaProxyEnabled);
        String resolvedWikiProxyUrl = StringUtils.hasText(wikiProxyUrl) ? wikiProxyUrl : defaultWikipediaProxyUrl;

        return ToolSearchSettings.builder()
                .wikipediaEnabled(resolvedWikiEnabled)
                .baikeEnabled(resolvedBaikeEnabled)
                .bochaEnabled(resolvedBochaEnabled)
                .bochaApiKey(resolvedBochaApiKey)
                .bochaEndpoint(resolvedBochaEndpoint)
                .baiduEnabled(resolvedBaiduEnabled)
                .searxEnabled(enabled)
                .searxUrl(resolvedSearxUrl)
                .serpApiKey(resolvedSerpKey)
                .serpApiEngine(resolvedSerpEngine)
                .wikipediaUserAgent(resolvedWikiUa)
                .wikipediaProxyEnabled(resolvedWikiProxyEnabled)
                .wikipediaProxyUrl(resolvedWikiProxyUrl)
                .build();
    }

    @Override
    public ToolSearchSettings updateSettings(ToolSearchSettings settings, Long updatedBy) {
        if (settings == null) {
            return getSettings();
        }
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_SEARX_ENABLED,
                String.valueOf(settings.isSearxEnabled()), updatedBy);
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_SEARX_URL,
                settings.getSearxUrl(), updatedBy);
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_SERPAPI_KEY,
                settings.getSerpApiKey(), updatedBy);
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_SERPAPI_ENGINE,
                settings.getSerpApiEngine(), updatedBy);
        if (settings.getWikipediaEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_ENABLED,
                    String.valueOf(settings.getWikipediaEnabled()), updatedBy);
        }
        if (settings.getBaikeEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_BAIKE_ENABLED,
                    String.valueOf(settings.getBaikeEnabled()), updatedBy);
        }
        if (settings.getBochaEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_ENABLED,
                    String.valueOf(settings.getBochaEnabled()), updatedBy);
        }
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_API_KEY,
                settings.getBochaApiKey(), updatedBy);
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_BOCHA_ENDPOINT,
                settings.getBochaEndpoint(), updatedBy);
        if (settings.getBaiduEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_BAIDU_ENABLED,
                    String.valueOf(settings.getBaiduEnabled()), updatedBy);
        }
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_UA,
                settings.getWikipediaUserAgent(), updatedBy);
        if (settings.getWikipediaProxyEnabled() != null) {
            appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_PROXY_ENABLED,
                    String.valueOf(settings.getWikipediaProxyEnabled()), updatedBy);
        }
        appConfigService.setValue(AppConfigKeys.TOOLS_SEARCH_WIKIPEDIA_PROXY_URL,
                settings.getWikipediaProxyUrl(), updatedBy);
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
}
