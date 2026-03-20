package com.harmony.backend.common.util;

import com.harmony.backend.common.config.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final ClientIpProperties properties;

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        if (properties.isTrustForwardHeaders()) {
            String forwarded = firstForwardedIp(request.getHeader("X-Forwarded-For"));
            if (StringUtils.hasText(forwarded)) {
                return normalizeIp(forwarded);
            }
            String proxyClientIp = normalizeIp(request.getHeader("Proxy-Client-IP"));
            if (StringUtils.hasText(proxyClientIp) && !"unknown".equalsIgnoreCase(proxyClientIp)) {
                return proxyClientIp;
            }
            String wlProxyClientIp = normalizeIp(request.getHeader("WL-Proxy-Client-IP"));
            if (StringUtils.hasText(wlProxyClientIp) && !"unknown".equalsIgnoreCase(wlProxyClientIp)) {
                return wlProxyClientIp;
            }
        }
        return normalizeIp(request.getRemoteAddr());
    }

    private String firstForwardedIp(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String first = value.split(",")[0].trim();
        if (!StringUtils.hasText(first) || "unknown".equalsIgnoreCase(first)) {
            return null;
        }
        return first;
    }

    private String normalizeIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return "unknown";
        }
        String value = ip.trim();
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }
        int colonIndex = value.indexOf(':');
        if (colonIndex > -1 && value.indexOf('.') > -1) {
            return value.substring(0, colonIndex);
        }
        return value;
    }
}
