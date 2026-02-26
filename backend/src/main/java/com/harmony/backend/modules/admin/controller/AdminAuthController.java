package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.LoginLogMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final UserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest loginRequest,
                                            HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();
        String deviceId = loginRequest.getDeviceId() != null && !loginRequest.getDeviceId().isBlank()
                ? loginRequest.getDeviceId()
                : UUID.randomUUID().toString();
        String ip = RequestUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        User user = userMapper.selectForLogin(username);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || !user.isNormal()) {
            return ApiResponse.error(401, "Unauthorized");
        }
        if (!"ADMIN".equals(user.getRole())) {
            recordLoginLog(user, ip, userAgent, false, "Forbidden");
            return ApiResponse.error(403, "Forbidden");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            recordLoginLog(user, ip, userAgent, false, "Wrong password");
            return ApiResponse.error(401, "Unauthorized");
        }

        String accessToken = jwtUtil.generateAccessToken(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole()
        );
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceId);

        userMapper.updateLastLoginTime(user.getId(), LocalDateTime.now());
        recordLoginLog(user, ip, userAgent, true, null);

        LoginResponse loginResponse = LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtUtil.getTokenRemainingTime(accessToken, false))
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
}

