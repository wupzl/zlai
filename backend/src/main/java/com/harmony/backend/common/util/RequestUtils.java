package com.harmony.backend.common.util;

import com.harmony.backend.common.constant.RequestAttributeConst;
import com.harmony.backend.common.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

@UtilityClass
public class RequestUtils {

    public static Optional<HttpServletRequest> getCurrentRequest() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return Optional.of(servletRequestAttributes.getRequest());
        }
        return Optional.empty();
    }

    public static Optional<User> getCurrentUser(HttpServletRequest request) {
        return Optional.ofNullable((User) request.getAttribute(RequestAttributeConst.CURRENT_USER));
    }

    public static Optional<User> getCurrentUser() {
        return getCurrentRequest().flatMap(RequestUtils::getCurrentUser);
    }

    public static User getCurrentUserOrThrow() {
        return getCurrentUser().orElseThrow(() -> new IllegalStateException("Current user not found"));
    }

    public static Long getCurrentUserId() {
        return getCurrentUser().map(User::getId).orElse(null);
    }

    public static Optional<String> getCurrentToken(HttpServletRequest request) {
        return Optional.ofNullable((String) request.getAttribute(RequestAttributeConst.CURRENT_TOKEN));
    }

    public static Optional<String> getCurrentToken() {
        return getCurrentRequest().flatMap(RequestUtils::getCurrentToken);
    }

    public static boolean isAuthenticated() {
        return getCurrentUser().isPresent();
    }

    public static boolean isAdmin() {
        return getCurrentUser().map(User::isAdmin).orElse(false);
    }

    public static String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}