package com.harmony.backend.modules.user.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.modules.user.controller.request.ChangePasswordRequest;
import com.harmony.backend.modules.user.controller.request.RefreshTokenRequest;
import com.harmony.backend.modules.user.controller.request.UserUpdateRequest;
import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.modules.user.service.IUserService;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * Update user profile (nickname, avatar).
     */
    @PutMapping("/update")
    public ApiResponse<Boolean> update(@RequestBody @Validated UserUpdateRequest userUpdateDTO,
                                       HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        Long currentUserId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            currentUserId = jwtUtil.getInternalUserIdFromToken(token, false);
        }
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
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ApiResponse.error(401, "Please login first");
        }

        String token = authHeader.substring(7);
        Long currentUserId;
        try {
            currentUserId = jwtUtil.getInternalUserIdFromToken(token, false);
        } catch (Exception e) {
            return ApiResponse.error(401, "Login expired, please login again");
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
            HttpServletRequest httpRequest) {
        log.info("Refresh token request");
        TokenResponse tokenResponse = userService.refreshToken(
                request.getRefreshToken(),
                httpRequest);
        return ApiResponse.success(tokenResponse);
    }

    /**
     * Logout (invalidate refresh token).
     */
    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(@RequestBody RefreshTokenRequest request) {
        log.info("Logout request");
        boolean result = userService.logout(request.getRefreshToken());
        return ApiResponse.success(result);
    }
}
