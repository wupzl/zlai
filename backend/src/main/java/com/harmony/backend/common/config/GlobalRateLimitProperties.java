package com.harmony.backend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.rate-limit.global")
public class GlobalRateLimitProperties {
    private boolean enabled = false;
    private boolean adminBypass = true;
    private int windowSeconds = 60;
    private int ipLimit = 300;
    private int userLimit = 200;
    private List<String> whitelistIps = new ArrayList<>();
    private List<String> whitelistPaths = new ArrayList<>();
}
