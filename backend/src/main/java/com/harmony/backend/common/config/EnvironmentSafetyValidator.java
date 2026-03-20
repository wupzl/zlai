package com.harmony.backend.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class EnvironmentSafetyValidator {

    private final Environment environment;
    private final AuthCookieProperties authCookieProperties;

    @PostConstruct
    public void validate() {
        String sameSite = authCookieProperties.getSameSite();
        if ("None".equalsIgnoreCase(sameSite) && !authCookieProperties.isSecure()) {
            throw new IllegalStateException("app.auth.cookies.same-site=None requires app.auth.cookies.secure=true");
        }
        if (isProfileActive("prod") && !authCookieProperties.isSecure()) {
            throw new IllegalStateException("Production profile requires app.auth.cookies.secure=true");
        }
        if (isProfileActive("prod") && !StringUtils.hasText(sameSite)) {
            throw new IllegalStateException("Production profile requires app.auth.cookies.same-site to be configured");
        }
    }

    private boolean isProfileActive(String profile) {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(profile::equalsIgnoreCase);
    }
}
