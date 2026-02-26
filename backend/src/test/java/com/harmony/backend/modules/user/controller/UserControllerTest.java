package com.harmony.backend.modules.user.controller;

import com.harmony.backend.modules.user.controller.response.TokenResponse;
import com.harmony.backend.modules.user.service.IUserService;
import com.harmony.backend.modules.user.service.UserSecurityService;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
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

    @Test
    void refresh_returnsNewAccessToken() throws Exception {
        when(userService.refreshToken(any(), any())).thenReturn(new TokenResponse("new-token"));

        mockMvc.perform(post("/api/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("new-token")));
    }

    @Test
    void logout_returnsSuccess() throws Exception {
        when(userService.logout(any())).thenReturn(true);

        mockMvc.perform(post("/api/user/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"r1\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"success\"")));
    }

    @Test
    void refresh_returnsBadRequestOnInvalidToken() throws Exception {
        when(userService.refreshToken(any(), any())).thenThrow(new RuntimeException("Refresh token invalid or expired"));

        mockMvc.perform(post("/api/user/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Refresh token invalid or expired")));
    }
}
