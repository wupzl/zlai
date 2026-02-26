package com.harmony.backend.modules.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.chat.rate-limit")
public class ChatRateLimitProperties {
    private int streamUserLimit = 200;
    private int streamIpLimit = 500;
    private int streamWindowSeconds = 60;

    private int messageUserLimit = 500;
    private int messageIpLimit = 1000;
    private int messageWindowSeconds = 60;

    private int sessionUserLimit = 200;
    private int sessionIpLimit = 500;
    private int sessionWindowSeconds = 60;

    private int defaultUserLimit = 800;
    private int defaultIpLimit = 2000;
    private int defaultWindowSeconds = 60;
}
