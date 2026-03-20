package com.harmony.backend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.network.client-ip")
public class ClientIpProperties {

    /**
     * Trust forwarding headers only behind a reverse proxy that rewrites them.
     */
    private boolean trustForwardHeaders = false;
}
