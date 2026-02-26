package com.harmony.backend.modules.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.modules.user.controller.request.ChangePasswordRequest;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.request.RegisterRequest;
import com.harmony.backend.modules.user.controller.request.UserUpdateRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import com.harmony.backend.common.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * User account service.
 */
public interface IUserService extends IService<User> {
    User getByUsername(String username);

    User getForLogin(String username);

    boolean updateLastLoginTime(Long id, LocalDateTime loginTime);

    boolean updateLastLogoutTime(Long id, LocalDateTime logoutTime);

    boolean updateTokenBalance(Long id, int delta);

    boolean lockUser(Long id, LocalDateTime lockedUntil);

    UserInfoVO register(RegisterRequest registerRequest);

    LoginResponse login(LoginRequest loginRequest, HttpServletRequest request);

    TokenResponse refreshToken(String refreshToken, HttpServletRequest request);

    boolean logout(String refreshToken);

    boolean updateUserInfo(Long currentUserId, UserUpdateRequest userUpdateDTO);

    boolean changePassword(Long currentUserId, ChangePasswordRequest dto);
}
