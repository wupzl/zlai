package com.harmony.backend.modules.admin.controller.request;

import lombok.Data;

import java.util.List;

@Data
public class GlobalRateLimitSettingsRequest {
    private Boolean enabled;
    private Boolean adminBypass;
    private Integer windowSeconds;
    private Integer ipLimit;
    private Integer userLimit;
    private List<String> whitelistIps;
    private List<String> whitelistPaths;
}
