package com.harmony.backend.modules.chat.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.service.ChatService;
import com.harmony.backend.modules.chat.service.ModelPricingService;
import com.harmony.backend.modules.chat.service.SessionService;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.modules.user.service.UserSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SessionService sessionService;
    @MockBean
    private ChatService chatService;
    @MockBean
    private ModelPricingService modelPricingService;
    @MockBean
    private BillingProperties billingProperties;
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private UserSecurityService userSecurityService;

    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(jwtUtil.getInternalUserIdFromToken(eq("token"), eq(false))).thenReturn(1L);
        when(jwtUtil.verifyAccessToken(eq("token"))).thenReturn(org.mockito.Mockito.mock(DecodedJWT.class));
        when(jwtUtil.getTokenRemainingTime(eq("token"), eq(false))).thenReturn(60L);
    }

    @Test
    void streamChat_returnsErrorWhenPromptEmpty() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Message content is empty")));
    }

    @Test
    void getSessions_returnsPageResult() throws Exception {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setChatId("chat-1");
        when(sessionService.getChatSessionsByUserId(eq(1L), eq(1), eq(20))).thenReturn(List.of(vo));
        when(sessionService.countChatSessionsByUserId(eq(1L))).thenReturn(1L);

        mockMvc.perform(get("/api/chat/sessions?page=1&size=20")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chat-1")));
    }

    @Test
    void createSession_returnsSuccess() throws Exception {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setChatId("chat-new");
        when(sessionService.createSession(eq(1L), any(), any(), any())).thenReturn(vo);

        mockMvc.perform(post("/api/chat/session")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chat-new")));
    }

    @Test
    void streamChat_returnsErrorWhenBalanceInsufficient() throws Exception {
        when(sessionService.checkChatBelong(eq(1L), eq("chat-1"))).thenReturn(true);
        when(chatService.chat(eq(1L), eq("chat-1"), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "event:message_chunk\ndata:{\"role\":\"assistant\",\"content\":\"Insufficient token balance\"}\n\n",
                        "event:done\ndata:{\"success\":false}\n\n"
                ));

        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .content("{\"chatId\":\"chat-1\",\"prompt\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Insufficient token balance")));
    }
}
