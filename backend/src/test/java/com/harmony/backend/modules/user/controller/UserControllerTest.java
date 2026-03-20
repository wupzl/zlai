package com.harmony.backend.modules.user.controller;

import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.common.config.AppCorsProperties;
import com.harmony.backend.common.filter.GlobalRateLimitFilter;
import com.harmony.backend.common.filter.JwtAuthenticationFilter;
import com.harmony.backend.common.util.AuthCookieService;
import com.harmony.backend.modules.user.service.IUserService;
import com.harmony.backend.modules.user.service.UserSecurityService;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IUserService userService;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private UserSecurityService userSecurityService;
    @MockBean
    private AppCorsProperties appCorsProperties;
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean
    private GlobalRateLimitFilter globalRateLimitFilter;
    @MockBean
    private AuthCookieService authCookieService;
    @MockBean(name = "webMvcTaskExecutor")
    private AsyncTaskExecutor webMvcTaskExecutor;

    @Test
    void refresh_returnsNewAccessToken() throws Exception {
        when(userService.refreshToken(any(), any(), any())).thenReturn(new TokenResponse(null));

        mockMvc.perform(post("/api/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":200")));
    }

    @Test
    void logout_returnsSuccess() throws Exception {
        when(userService.logout(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/user/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"success\"")));
    }

    @Test
    void refresh_returnsBadRequestOnInvalidToken() throws Exception {
        when(userService.refreshToken(any(), any(), any())).thenThrow(new BusinessException(401, "Refresh token invalid or expired"));

        mockMvc.perform(post("/api/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Refresh token invalid or expired")));
    }

    @Test
    void logout_withoutRefreshToken_returnsFalse() throws Exception {
        mockMvc.perform(post("/api/user/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":false")));
    }
}
