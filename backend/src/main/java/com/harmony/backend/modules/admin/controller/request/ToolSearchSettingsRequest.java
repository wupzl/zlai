package com.harmony.backend.modules.admin.controller.request;

import lombok.Data;

@Data
public class ToolSearchSettingsRequest {
    private Boolean wikipediaEnabled;
    private Boolean baikeEnabled;
    private Boolean bochaEnabled;
    private String bochaApiKey;
    private String bochaEndpoint;
    private Boolean baiduEnabled;
    private Boolean searxEnabled;
    private String searxUrl;
    private String serpApiKey;
    private String serpApiEngine;
    private String wikipediaUserAgent;
    private Boolean wikipediaProxyEnabled;
    private String wikipediaProxyUrl;
}
