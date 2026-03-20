package com.harmony.backend.common.util;

import com.harmony.backend.common.config.AuthCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AuthCookieService {

    private final AuthCookieProperties properties;

    public void writeAccessToken(HttpServletResponse response, String token, long maxAgeSeconds) {
        addCookie(response, properties.getAccessTokenName(), token, maxAgeSeconds);
    }

    public void writeRefreshToken(HttpServletResponse response, String token, long maxAgeSeconds) {
        addCookie(response, properties.getRefreshTokenName(), token, maxAgeSeconds);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, properties.getAccessTokenName(), "", 0);
        addCookie(response, properties.getRefreshTokenName(), "", 0);
    }

    public String resolveAccessToken(HttpServletRequest request) {
        return resolveCookieValue(request, properties.getAccessTokenName());
    }

    public String resolveRefreshToken(HttpServletRequest request) {
        return resolveCookieValue(request, properties.getRefreshTokenName());
    }

    private String resolveCookieValue(HttpServletRequest request, String cookieName) {
        if (request == null || !StringUtils.hasText(cookieName)) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null && cookieName.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void addCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .path(properties.getPath())
                .sameSite(properties.getSameSite())
                .maxAge(Math.max(0, maxAgeSeconds));
        if (StringUtils.hasText(properties.getDomain())) {
            builder.domain(properties.getDomain());
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }
}
