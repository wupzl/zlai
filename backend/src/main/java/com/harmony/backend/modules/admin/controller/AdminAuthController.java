package com.harmony.backend.modules.admin.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.LoginLogMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.AuthCookieService;
import com.harmony.backend.common.util.ClientIpResolver;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import com.harmony.backend.modules.user.service.UserSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final UserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthCookieService authCookieService;
    private final ClientIpResolver clientIpResolver;
    private final UserSecurityService userSecurityService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String LOGIN_FAILURE_KEY_PREFIX = "login:fail:";

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest loginRequest,
                                            HttpServletRequest request,
                                            HttpServletResponse response) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();
        String deviceId = loginRequest.getDeviceId() != null && !loginRequest.getDeviceId().isBlank()
                ? loginRequest.getDeviceId()
                : UUID.randomUUID().toString();
        String ip = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");

        User user = userMapper.selectForLogin(username);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || !user.isNormal()) {
            handleLoginFailure(username, ip, userAgent, "Unauthorized");
            return ApiResponse.error(401, "Unauthorized");
        }
        if ("LOCKED".equals(user.getStatus()) || user.isLocked()) {
            recordLoginLog(user, ip, userAgent, false, "Locked");
            return ApiResponse.error(423, "Account is locked");
        }
        if (!"ADMIN".equals(user.getRole())) {
            handleLoginFailure(username, ip, userAgent, "Forbidden");
            return ApiResponse.error(403, "Forbidden");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            handleLoginFailure(username, ip, userAgent, "Wrong password");
            return ApiResponse.error(401, "Unauthorized");
        }

        String accessToken = jwtUtil.generateAccessToken(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole()
        );
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceId);
        authCookieService.writeAccessToken(response, accessToken, jwtUtil.getTokenRemainingTime(accessToken, false));
        authCookieService.writeRefreshToken(response, refreshToken, jwtUtil.getTokenRemainingTime(refreshToken, true));
        redisTemplate.delete(LOGIN_FAILURE_KEY_PREFIX + user.getUsername());
        DecodedJWT decodedAccess = jwtUtil.verifyAccessToken(accessToken);
        if (decodedAccess != null) {
            userSecurityService.recordTokenIssuedTime(user.getId(), decodedAccess.getIssuedAt());
        }

        userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());
        recordLoginLog(user, ip, userAgent, true, null);

        LoginResponse loginResponse = LoginResponse.builder()
                .expiresIn(jwtUtil.getTokenRemainingTime(accessToken, false))
                .tokenType("Cookie")
                .loginTime(LocalDateTime.now())
                .userInfo(UserInfoVO.builder()
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .avatarUrl(user.getAvatarUrl())
                        .balance(user.getTokenBalance())
                        .role(user.getRole())
                        .last_password_change(user.getLastPasswordChange())
                        .build())
                .build();

        return ApiResponse.success(loginResponse);
    }

    private void recordLoginLog(User user, String ip, String userAgent, boolean success, String failReason) {
        if (user == null || user.getId() == null) {
            return;
        }
        LoginLog loginLog = LoginLog.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .ipAddress(ip)
                .userAgent(userAgent)
                .success(success)
                .failReason(failReason)
                .loginTime(LocalDateTime.now())
                .build();
        loginLogMapper.insert(loginLog);
    }

    private void handleLoginFailure(String username, String ip, String userAgent, String message) {
        User user = userMapper.selectForLogin(username);
        if (user == null) {
            log.warn("Admin login failed - user not found: account={}, ip={}", username, ip);
            return;
        }

        String failKey = LOGIN_FAILURE_KEY_PREFIX + user.getUsername();
        Long failCount = redisTemplate.opsForValue().increment(failKey);
        if (failCount == 1) {
            redisTemplate.expire(failKey, 1, TimeUnit.HOURS);
        }
        if (failCount != null && failCount >= 5) {
            userMapper.lockUser(user.getId(), LocalDateTime.now().plusHours(1));
        }
        recordLoginLog(user, ip, userAgent, false, message);
    }
}

