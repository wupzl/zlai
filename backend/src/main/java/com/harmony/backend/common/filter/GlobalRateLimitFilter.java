package com.harmony.backend.common.filter;

import com.harmony.backend.common.config.GlobalRateLimitProperties;
import com.harmony.backend.common.model.GlobalRateLimitSettings;
import com.harmony.backend.common.service.RedisTokenBucketService;
import com.harmony.backend.common.service.GlobalRateLimitSettingsService;
import com.harmony.backend.common.util.AuthCookieService;
import com.harmony.backend.common.util.ClientIpResolver;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class GlobalRateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "rate_limit:global:";

    private final GlobalRateLimitProperties properties;
    private final GlobalRateLimitSettingsService settingsService;
    private final JwtUtil jwtUtil;
    private final RedisTokenBucketService tokenBucketService;
    private final AuthCookieService authCookieService;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        GlobalRateLimitSettings settings = settingsService.getSettings();
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled())) {
            return true;
        }
        String path = request.getRequestURI();
        if (!StringUtils.hasText(path)) {
            return false;
        }
        return isWhitelistedPath(path, settings);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        GlobalRateLimitSettings settings = settingsService.getSettings();
        if (settings == null || !Boolean.TRUE.equals(settings.getEnabled())) {
            filterChain.doFilter(request, response);
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = RequestUtils.extractToken(request);
        if (!StringUtils.hasText(token)) {
            token = authCookieService.resolveAccessToken(request);
        }
        Long currentUserId = resolveUserId(token);
        if (Boolean.TRUE.equals(settings.getAdminBypass()) && isAdminRequest(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIpResolver.resolve(request);
        if (isWhitelistedIp(ip, settings)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (!checkLimit(ip, currentUserId, settings)) {
                sendTooMany(response);
                return;
            }
        } catch (Exception ex) {
            log.warn("Global rate limit check failed: {}", ex.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean checkLimit(String ip, Long userId, GlobalRateLimitSettings settings) {
        int window = settings.getWindowSeconds() != null ? settings.getWindowSeconds() : properties.getWindowSeconds();
        int ipLimit = settings.getIpLimit() != null ? settings.getIpLimit() : properties.getIpLimit();
        int userLimit = settings.getUserLimit() != null ? settings.getUserLimit() : properties.getUserLimit();

        String ipKey = KEY_PREFIX + "ip:" + ip;
        if (!tokenBucketService.tryConsume(ipKey, ipLimit, window)) {
            return false;
        }

        if (userId != null) {
            String userKey = KEY_PREFIX + "user:" + userId;
            return tokenBucketService.tryConsume(userKey, userLimit, window);
        }

        return true;
    }

    private Long resolveUserId(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return jwtUtil.getInternalUserIdFromToken(token, false);
    }

    private boolean isAdminRequest(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return "ADMIN".equalsIgnoreCase(jwtUtil.getRoleFromToken(token));
    }

    private boolean isWhitelistedPath(String path, GlobalRateLimitSettings settings) {
        List<String> paths = settings.getWhitelistPaths();
        if (paths == null || paths.isEmpty()) {
            return false;
        }
        for (String prefix : paths) {
            if (StringUtils.hasText(prefix) && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhitelistedIp(String ip, GlobalRateLimitSettings settings) {
        List<String> ips = settings.getWhitelistIps();
        if (ips == null || ips.isEmpty() || !StringUtils.hasText(ip)) {
            return false;
        }
        String normalized = normalizeIp(ip);
        for (String item : ips) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String candidate = item.trim();
            if (candidate.contains("/")) {
                if (matchesCidr(normalized, candidate)) {
                    return true;
                }
                continue;
            }
            if (candidate.endsWith("*")) {
                String prefix = candidate.substring(0, candidate.length() - 1);
                if (normalized.startsWith(prefix)) {
                    return true;
                }
                continue;
            }
            if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return "";
        }
        String trimmed = ip.trim();
        if (trimmed.contains(",")) {
            trimmed = trimmed.split(",")[0].trim();
        }
        int portIdx = trimmed.indexOf(':');
        if (portIdx > -1 && trimmed.indexOf('.') > -1) {
            trimmed = trimmed.substring(0, portIdx);
        }
        return trimmed;
    }

    private boolean matchesCidr(String ip, String cidr) {
        if (!StringUtils.hasText(ip) || !StringUtils.hasText(cidr)) {
            return false;
        }
        if (!ip.contains(".")) {
            return false;
        }
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        String base = parts[0].trim();
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        long ipVal = ipv4ToLong(ip);
        long baseVal = ipv4ToLong(base);
        long mask = prefix == 0 ? 0 : (-1L << (32 - prefix)) & 0xFFFFFFFFL;
        return (ipVal & mask) == (baseVal & mask);
    }

    private long ipv4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return 0;
        }
        long result = 0;
        for (String part : parts) {
            int val;
            try {
                val = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                return 0;
            }
            result = (result << 8) + (val & 0xFF);
        }
        return result & 0xFFFFFFFFL;
    }

    private void sendTooMany(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"Too many requests\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
    }
}
