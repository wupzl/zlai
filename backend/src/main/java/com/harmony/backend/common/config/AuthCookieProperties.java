package com.harmony.backend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.auth.cookies")
public class AuthCookieProperties {

    private String accessTokenName = "zlai_access_token";

    private String refreshTokenName = "zlai_refresh_token";

    private String path = "/";

    private String domain;

    private boolean secure = false;

    private String sameSite = "Lax";
}
