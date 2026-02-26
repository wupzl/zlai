package com.harmony.backend.common.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.harmony.backend.common.constant.RequestAttributeConst;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.user.service.UserSecurityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final UserSecurityService userSecurityService;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // SSE/stream endpoints are processed in async dispatch phases as well.
        // The JWT filter must run there, otherwise Spring Security may treat the
        // async dispatch as anonymous and deny access mid-stream.
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = RequestUtils.extractToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        DecodedJWT decodedJWT = jwtUtil.verifyAccessToken(token);
        if (decodedJWT == null) {
            sendError(response, "Invalid or expired token");
            return;
        }

        Long internalId = jwtUtil.getInternalUserIdFromToken(token, false);
        if (internalId == null) {
            sendError(response, "Invalid token format");
            return;
        }

        if (userSecurityService.isTokenBlacklisted(token)) {
            sendError(response, "Token is blacklisted");
            return;
        }

        User user = userMapper.selectById(internalId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || !user.isNormal()) {
            sendError(response, "User is not available");
            return;
        }

        Date issuedAt = decodedJWT.getIssuedAt();
        if (!userSecurityService.validateTokenIssuedTime(internalId, issuedAt)) {
            sendError(response, "Token is no longer valid");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        request.setAttribute(RequestAttributeConst.CURRENT_USER, user);
        request.setAttribute(RequestAttributeConst.CURRENT_TOKEN, token);
        userSecurityService.updateLastActiveTime(internalId);

        MDC.put("userId", String.valueOf(internalId));
        MDC.put("role", String.valueOf(user.getRole()));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("role");
        }
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":401,\"message\":\"%s\",\"data\":null,\"timestamp\":%d}",
                message, System.currentTimeMillis()));
    }
}
