package com.harmony.backend.ai.tool.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSearchSettings {
    private Boolean wikipediaEnabled;
    private Boolean baikeEnabled;
    private Boolean bochaEnabled;
    private String bochaApiKey;
    private String bochaEndpoint;
    private Boolean baiduEnabled;
    private boolean searxEnabled;
    private String searxUrl;
    private String serpApiKey;
    private String serpApiEngine;
    private String wikipediaUserAgent;
    private Boolean wikipediaProxyEnabled;
    private String wikipediaProxyUrl;
}
