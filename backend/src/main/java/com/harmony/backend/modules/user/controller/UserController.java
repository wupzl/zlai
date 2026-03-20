package com.harmony.backend.modules.user.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.util.AuthCookieService;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.user.controller.request.ChangePasswordRequest;
import com.harmony.backend.modules.user.controller.request.RefreshTokenRequest;
import com.harmony.backend.modules.user.controller.request.UserUpdateRequest;
import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import com.harmony.backend.modules.user.service.IUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User profile and token endpoints.
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final IUserService userService;
    private final JwtUtil jwtUtil;
    private final AuthCookieService authCookieService;

    /**
     * Update user profile (nickname, avatar).
     */
    @PutMapping("/update")
    public ApiResponse<Boolean> update(@RequestBody @Validated UserUpdateRequest userUpdateDTO,
                                       HttpServletRequest request) {
        Long currentUserId = RequestUtils.getCurrentUserId();
        boolean hasUpdates = userUpdateDTO.getNickname() != null ||
                userUpdateDTO.getAvatarUrl() != null;
        if (hasUpdates) {
            boolean result = userService.updateUserInfo(currentUserId, userUpdateDTO);
            return ApiResponse.success(result);
        }
        return ApiResponse.error("No fields to update");
    }

    /**
     * Change password.
     */
    @PutMapping("/change-password")
    public ApiResponse<Boolean> changePassword(@RequestBody ChangePasswordRequest dto,
                                               HttpServletRequest request) {
        Long currentUserId = RequestUtils.getCurrentUserId();
        if (currentUserId == null) {
            return ApiResponse.error(401, "Please login first");
        }

        List<String> dtoErrors = dto.validate();
        if (!dtoErrors.isEmpty()) {
            return ApiResponse.error(400, String.join("; ", dtoErrors));
        }

        try {
            boolean result = userService.changePassword(currentUserId, dto);
            if (result) {
                return ApiResponse.success(true);
            }
            return ApiResponse.error("Password update failed");
        } catch (BusinessException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("Change password error", e);
            return ApiResponse.error("System error, please retry later");
        }
    }

    /**
     * Refresh access token.
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(
            @Validated @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        log.info("Refresh token request");
        String refreshToken = resolveRefreshToken(httpRequest, request);
        TokenResponse tokenResponse = userService.refreshToken(
                refreshToken,
                httpRequest,
                httpResponse);
        return ApiResponse.success(tokenResponse);
    }

    @GetMapping("/check")
    public ApiResponse<UserInfoVO> check() {
        com.harmony.backend.common.entity.User currentUser = RequestUtils.getCurrentUser().orElse(null);
        if (currentUser == null) {
            return ApiResponse.error(401, "Unauthorized");
        }
        return ApiResponse.success(UserInfoVO.builder()
                .username(currentUser.getUsername())
                .nickname(currentUser.getNickname())
                .avatarUrl(currentUser.getAvatarUrl())
                .balance(currentUser.getTokenBalance())
                .role(currentUser.getRole())
                .last_password_change(currentUser.getLastPasswordChange())
                .build());
    }

    /**
     * Logout (invalidate refresh token).
     */
    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        log.info("Logout request");
        String refreshToken = resolveRefreshToken(httpRequest, request);
        String accessToken = resolveAccessToken(httpRequest);
        boolean result = userService.logout(refreshToken, accessToken);
        authCookieService.clearAuthCookies(httpResponse);
        return ApiResponse.success(result);
    }

    private String resolveRefreshToken(HttpServletRequest request, RefreshTokenRequest body) {
        if (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isBlank()) {
            return body.getRefreshToken();
        }
        return authCookieService.resolveRefreshToken(request);
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String accessToken = RequestUtils.extractBearerToken(request);
        if (accessToken != null && !accessToken.isBlank()) {
            return accessToken;
        }
        return authCookieService.resolveAccessToken(request);
    }
}
