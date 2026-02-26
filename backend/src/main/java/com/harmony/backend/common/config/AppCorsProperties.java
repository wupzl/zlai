package com.harmony.backend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "app.cors")
public class AppCorsProperties {

    /**
     * Exact allowed origins. Leave empty to use allowedOriginPatterns only.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * Allowed origin patterns (e.g. https://*.example.com).
     */
    private List<String> allowedOriginPatterns = List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "https://localhost:*",
            "https://127.0.0.1:*"
    );

    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");

    private List<String> allowedHeaders = List.of("*");

    private List<String> exposedHeaders = List.of(
            "Authorization",
            "Content-Type",
            "X-Access-Token",
            "X-Refresh-Token",
            "Content-Disposition",
            "X-Total-Count",
            "X-Page-Size",
            "X-Current-Page"
    );

    private boolean allowCredentials = true;

    private long maxAge = 1800L;
}
