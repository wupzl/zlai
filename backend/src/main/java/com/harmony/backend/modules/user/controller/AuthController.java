package com.harmony.backend.modules.user.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.request.RegisterRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.controller.response.UserInfoVO;
import com.harmony.backend.modules.user.service.IUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * User authentication endpoints.
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final IUserService userService;

    /**
     * Register a new user.
     */
    @PostMapping("/register")
    public ApiResponse<UserInfoVO> register(@Validated @RequestBody RegisterRequest registerRequest) {
        log.info("Register request: username={}", registerRequest.getUsername());
        UserInfoVO userInfo = userService.register(registerRequest);
        return ApiResponse.success(userInfo);
    }

    /**
     * Login and return tokens.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest loginRequest, HttpServletRequest httpRequest) {
        log.info("Login request: username={}", loginRequest.getUsername());
        LoginResponse loginResponse = userService.login(loginRequest, httpRequest);
        return ApiResponse.success(loginResponse);
    }
}
