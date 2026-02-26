// Location: src/main/java/com/harmony/backend/common/interceptor/JwtAuthInterceptor.java

package com.harmony.backend.common.interceptor;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.harmony.backend.common.constant.RequestAttributeConst;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.user.service.UserSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Date;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final UserSecurityService userSecurityService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String requestURI = request.getRequestURI();
        String method = request.getMethod();

        // Allow OPTIONS (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            log.debug("Allow OPTIONS preflight: {}", requestURI);
            return true;
        }

        log.debug("Auth check: {} {}", method, requestURI);

        // 1) Extract token
        String token = RequestUtils.extractToken(request);
        if (!StringUtils.hasText(token)) {
            sendError(response, 401, "Missing token");
            return false;
        }

        // 2) Verify access token
        DecodedJWT decodedJWT = jwtUtil.verifyAccessToken(token);
        if (decodedJWT == null) {
            sendError(response, 401, "Invalid or expired token");
            return false;
        }

        // 3) Get user id
        Long internalId = jwtUtil.getInternalUserIdFromToken(token, false);
        if (internalId == null) {
            sendError(response, 401, "Invalid token format");
            return false;
        }

        // 4) Check blacklist
        if (userSecurityService.isTokenBlacklisted(token)) {
            sendError(response, 401, "Token blacklisted");
            return false;
        }

        // 5) Load user
        User user = userMapper.selectById(internalId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || !user.isNormal()) {
            sendError(response, 401, "User not found or disabled");
            return false;
        }

        // 6) Validate issued-at time
        Date issuedAt = decodedJWT.getIssuedAt();
        if (!userSecurityService.validateTokenIssuedTime(internalId, issuedAt)) {
            sendError(response, 401, "Token expired, please login again");
            return false;
        }

        // 7) Attach user to request
        request.setAttribute(RequestAttributeConst.CURRENT_USER, user);
        request.setAttribute(RequestAttributeConst.CURRENT_TOKEN, token);

        // 8) Update last active time
        userSecurityService.updateLastActiveTime(internalId);

        log.debug("Auth ok: userId={}, username={}, path={}",
                user.getId(), user.getUsername(), requestURI);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        if (ex != null) {
            log.error("Request error", ex);
        }
    }

    private void sendError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\"}", code, message));
    }
}
