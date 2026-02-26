package com.harmony.backend.common.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GlobalRateLimitSettings {
    private Boolean enabled;
    private Boolean adminBypass;
    private Integer windowSeconds;
    private Integer ipLimit;
    private Integer userLimit;
    private List<String> whitelistIps;
    private List<String> whitelistPaths;
}
