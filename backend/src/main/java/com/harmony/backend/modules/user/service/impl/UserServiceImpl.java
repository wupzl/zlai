
package com.harmony.backend.modules.user.service.impl;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.constant.PasswordStrength;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.event.UserLoginEvent;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.mapper.LoginLogMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.common.validator.PasswordValidator;
import com.harmony.backend.modules.user.controller.request.ChangePasswordRequest;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.request.RegisterRequest;
import com.harmony.backend.modules.user.controller.request.UserUpdateRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import com.harmony.backend.modules.user.service.IUserService;
import com.harmony.backend.modules.user.service.UserSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * User account service implementation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final UserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final RedisTemplate<String, String> redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final PasswordValidator passwordValidator;
    private final UserSecurityService userSecurityService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.harmony.backend.ai.rag.service.OcrSettingsService ocrSettingsService;
    @Value("${app.user.default-token-balance:200000}")
    private int defaultTokenBalance;

    // Redis Key
    private static final String LOGIN_FAILURE_KEY_PREFIX = "login:fail:";
    private static final String REFRESH_TOKEN_BLACKLIST_PREFIX = "refresh:blacklist:";

    @Override
    public User getByUsername(String username) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, username)
                .eq(User::getDeleted, false);
        return this.getOne(queryWrapper);
    }

    @Override
    public User getForLogin(String username) {
        return this.baseMapper.selectForLogin(username);
    }

    @Override
    public boolean updateLastLoginTime(Long id, LocalDateTime loginTime) {
        return this.baseMapper.updateLastLoginTime(id, loginTime) > 0;
    }

    @Override
    public boolean updateLastLogoutTime(Long id, LocalDateTime logoutTime) {
        return this.baseMapper.updateLastLogoutTime(id, logoutTime) > 0;
    }

    @Override
    public boolean updateTokenBalance(Long id, int delta) {
        return this.baseMapper.updateTokenBalance(id, delta) > 0;
    }

    @Override
    public boolean lockUser(Long id, LocalDateTime lockedUntil) {
        return this.baseMapper.lockUser(id, lockedUntil) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVO register(RegisterRequest registerRequest) {
        User existingByUsername = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, registerRequest.getUsername())
                        .eq(User::getDeleted, false)
        );
        if (existingByUsername != null) {
            throw new RuntimeException("Username already exists");
        }

        User user = User.builder()
                .username(registerRequest.getUsername())
                .password(passwordEncoder.encode(registerRequest.getPassword()))
                .nickname(registerRequest.getNickname())
                .avatarUrl(registerRequest.getAvatarUrl())
                .status("ACTIVE")
                .deleted(false)
                .role("USER")
                .tokenBalance(defaultTokenBalance)
                .ocrBalance(ocrSettingsService.getSettings().getDefaultUserQuota())
                .build();

        userMapper.insert(user);
        log.info("User registered: username={}", user.getUsername());
        return convertToUserVO(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest loginRequest, HttpServletRequest request) {
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();
        String deviceId = StringUtils.hasText(loginRequest.getDeviceId()) ?
                loginRequest.getDeviceId() : UUID.randomUUID().toString();
        String ip = RequestUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        LocalDateTime now = LocalDateTime.now();

        User user = userMapper.selectForLogin(username);

        if (user == null) {
            handleLoginFailure(username, ip, userAgent, "User not found");
            throw new RuntimeException("Invalid username or password");
        }

        if ("LOCKED".equals(user.getStatus())) {
            throw new RuntimeException("Account is locked");
        }

        if (!user.isNormal()) {
            if (user.isDisabled()) {
                throw new RuntimeException("Account is disabled");
            } else if (user.isLocked()) {
                throw new RuntimeException("Account is locked");
            }
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            handleLoginFailure(username, ip, userAgent, "Wrong password");
            throw new RuntimeException("Invalid username or password");
        }

        String accessToken = jwtUtil.generateAccessToken(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole()
        );
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), deviceId);

        DecodedJWT decodedAccess = jwtUtil.verifyAccessToken(accessToken);
        if (decodedAccess != null) {
            userSecurityService.recordTokenIssuedTime(user.getId(), decodedAccess.getIssuedAt());
        }

        userMapper.updateLastLoginTime(user.getId(), now);

        String failKey = LOGIN_FAILURE_KEY_PREFIX + username;
        redisTemplate.delete(failKey);

        log.info("User login success: userId={}, username={}, ip={}", user.getId(), user.getUsername(), ip);
        recordLoginLog(user, ip, userAgent, true, null);
        eventPublisher.publishEvent(new UserLoginEvent(this, user.getId()));

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtUtil.getTokenRemainingTime(accessToken, false))
                .userInfo(convertToUserVO(user))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse refreshToken(String refreshToken, HttpServletRequest request) {
        log.info("Refresh token request");

        String blacklistKey = REFRESH_TOKEN_BLACKLIST_PREFIX + refreshToken;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            log.warn("Refresh token is blacklisted");
            throw new RuntimeException("Refresh token invalid");
        }

        Long userId = jwtUtil.getInternalUserIdFromToken(refreshToken, true);
        if (userId == null) {
            log.warn("Refresh token invalid or expired");
            throw new RuntimeException("Refresh token invalid or expired");
        }

        User user = userMapper.selectById(userId);
        if (user == null || Boolean.TRUE.equals(user.getDeleted()) || !user.isNormal()) {
            log.warn("User not found or status invalid: userId={}", userId);
            throw new RuntimeException("User not found or status invalid");
        }

        String newAccessToken = jwtUtil.generateAccessToken(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole()
        );

        DecodedJWT decodedAccess = jwtUtil.verifyAccessToken(newAccessToken);
        if (decodedAccess != null) {
            userSecurityService.recordTokenIssuedTime(user.getId(), decodedAccess.getIssuedAt());
        }

        log.info("Token refreshed: userId={}, username={}",
                user.getId(), user.getUsername());

        return new TokenResponse(newAccessToken);
    }

    private UserInfoVO convertToUserVO(User user) {
        return UserInfoVO.builder()
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .balance(user.getTokenBalance())
                .username(user.getUsername())
                .last_password_change(user.getLastPasswordChange())
                .role(user.getRole())
                .build();
    }

    private void handleLoginFailure(String username, String ip, String userAgent, String message) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (user == null) {
            log.warn("Login failed - user not found: account={}, ip={}", username, ip);
            return;
        }

        String failKey = LOGIN_FAILURE_KEY_PREFIX + user.getUsername();
        Long failCount = redisTemplate.opsForValue().increment(failKey);

        if (failCount == 1) {
            redisTemplate.expire(failKey, 1, TimeUnit.HOURS);
        }

        if (failCount >= 5) {
            lockUser(user.getId(), LocalDateTime.now().plusHours(1));
            log.warn("Account locked: username={}, failCount={}, ip={}", user.getUsername(), failCount, ip);
        }

        log.warn("Login failed: username={}, reason={}, ip={}, failCount={}",
                user.getUsername(), message, ip, failCount);

        recordLoginLog(user, ip, userAgent, false, message);
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

    /**
     * Logout (blacklist refresh token)
     */
    @Override
    @Transactional
    public boolean logout(String refreshToken) {
        Long remainingTime = jwtUtil.getTokenRemainingTime(refreshToken, true);
        if (remainingTime > 0) {
            String blacklistKey = REFRESH_TOKEN_BLACKLIST_PREFIX + refreshToken;
            redisTemplate.opsForValue().set(blacklistKey, "1", remainingTime, TimeUnit.SECONDS);
            log.info("Logout success, refresh token blacklisted, remainingTime={}s", remainingTime);
            return true;
        }
        return false;
    }

    @Override
    public boolean updateUserInfo(Long userId, UserUpdateRequest userUpdateDTO) {
        User user = new User();
        user.setId(userId);

        if (userUpdateDTO.getNickname() != null) {
            user.setNickname(userUpdateDTO.getNickname());
        }
        if (userUpdateDTO.getAvatarUrl() != null) {
            user.setAvatarUrl(userUpdateDTO.getAvatarUrl());
        }

        return updateById(user);
    }

    @Override
    @Transactional
    public boolean changePassword(Long userId, ChangePasswordRequest dto) {
        List<String> dtoErrors = dto.validate();
        if (!dtoErrors.isEmpty()) {
            throw new BusinessException(String.join("; ", dtoErrors));
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("User not found");
        }

        if (!passwordEncoder.matches(dto.getCurrPassword(), user.getPassword())) {
            throw new BusinessException("Current password incorrect");
        }

        PasswordValidator.ValidationResult validationResult =
                passwordValidator.validate(
                        dto.getNewPassword(),
                        user.getUsername(),
                        dto.getCurrPassword()
                );

        if (!validationResult.isValid()) {
            throw new BusinessException(validationResult.getErrorMessage());
        }

        PasswordStrength strength = passwordValidator.checkStrength(dto.getNewPassword());
        if (strength.getLevel() < PasswordStrength.MEDIUM.getLevel()) {
            throw new BusinessException("Password too weak");
        }

        String encodedPassword = passwordEncoder.encode(dto.getNewPassword());

        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setPassword(encodedPassword);
        updateUser.setLastPasswordChange(LocalDateTime.now());

        boolean success = userMapper.updateById(updateUser) > 0;

        if (success) {
            logPasswordChange(userId, strength);
        }

        return success;
    }

    private void logPasswordChange(Long userId, PasswordStrength strength) {
        log.info("User[{}] password changed, strength={}", userId, strength.getDescription());
    }
}
