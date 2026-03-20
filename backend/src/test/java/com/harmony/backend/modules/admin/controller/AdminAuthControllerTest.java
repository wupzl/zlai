package com.harmony.backend.modules.admin.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.LoginLogMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.common.util.AuthCookieService;
import com.harmony.backend.common.util.ClientIpResolver;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.modules.user.controller.request.LoginRequest;
import com.harmony.backend.modules.user.controller.response.LoginResponse;
import com.harmony.backend.modules.user.service.UserSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthControllerTest {

    @Test
    void login_recordsTokenIssuedTimeFromJwtIssuedAt() {
        UserMapper userMapper = mock(UserMapper.class);
        LoginLogMapper loginLogMapper = mock(LoginLogMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        AuthCookieService authCookieService = mock(AuthCookieService.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
        UserSecurityService userSecurityService = mock(UserSecurityService.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AdminAuthController controller = new AdminAuthController(
                userMapper,
                loginLogMapper,
                passwordEncoder,
                jwtUtil,
                authCookieService,
                clientIpResolver,
                userSecurityService,
                redisTemplate
        );

        LoginRequest requestBody = new LoginRequest();
        requestBody.setUsername("admin");
        requestBody.setPassword("secret");

        User admin = User.builder()
                .id(7L)
                .username("admin")
                .nickname("Admin")
                .password("encoded")
                .role("ADMIN")
                .status("ACTIVE")
                .deleted(false)
                .tokenBalance(100L)
                .lastPasswordChange(LocalDateTime.now())
                .build();

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        DecodedJWT decodedJWT = mock(DecodedJWT.class);
        Date issuedAt = new Date(1_700_000_000_000L);

        when(clientIpResolver.resolve(httpRequest)).thenReturn("127.0.0.1");
        when(httpRequest.getHeader("User-Agent")).thenReturn("JUnit");
        when(userMapper.selectForLogin("admin")).thenReturn(admin);
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(jwtUtil.generateAccessToken(anyLong(), eq("admin"), eq("Admin"), eq("ADMIN"))).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(eq(7L), any())).thenReturn("refresh-token");
        when(jwtUtil.getTokenRemainingTime("access-token", false)).thenReturn(3600L);
        when(jwtUtil.getTokenRemainingTime("refresh-token", true)).thenReturn(7200L);
        when(jwtUtil.verifyAccessToken("access-token")).thenReturn(decodedJWT);
        when(decodedJWT.getIssuedAt()).thenReturn(issuedAt);

        ApiResponse<LoginResponse> response = controller.login(requestBody, httpRequest, httpResponse);

        ArgumentCaptor<Date> dateCaptor = ArgumentCaptor.forClass(Date.class);
        verify(userSecurityService).recordTokenIssuedTime(eq(7L), dateCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(issuedAt);
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().getTokenType()).isEqualTo("Cookie");
    }
}
