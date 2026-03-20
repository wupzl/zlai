package com.harmony.backend.modules.chat.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.common.config.AppCorsProperties;
import com.harmony.backend.common.constant.RequestAttributeConst;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.filter.GlobalRateLimitFilter;
import com.harmony.backend.common.filter.JwtAuthenticationFilter;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.config.ChatRateLimitProperties;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import com.harmony.backend.modules.chat.controller.response.GroundedChatResponse;
import com.harmony.backend.modules.chat.service.ChatService;
import com.harmony.backend.modules.chat.service.ModelPricingService;
import com.harmony.backend.modules.chat.service.SessionService;
import com.harmony.backend.modules.chat.service.support.ChatSessionCacheService;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.common.service.RedisTokenBucketService;
import com.harmony.backend.common.util.ClientIpResolver;
import com.harmony.backend.common.util.JwtUtil;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.modules.user.service.UserSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.AsyncTaskExecutor;
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

@WebMvcTest(
        controllers = ChatController.class,
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        }
)
@AutoConfigureMockMvc(addFilters = false)
class ChatControllerTest {
    private static final User CURRENT_USER = User.builder()
            .id(1L)
            .username("test01")
            .role("USER")
            .status("ACTIVE")
            .deleted(false)
            .build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SessionService sessionService;
    @MockBean
    private ChatService chatService;
    @MockBean
    private ModelPricingService modelPricingService;
    @MockBean
    private BillingProperties billingProperties;
    @MockBean
    private ChatRateLimitProperties chatRateLimitProperties;
    @MockBean
    private RedisTokenBucketService redisTokenBucketService;
    @MockBean
    private ChatSessionCacheService chatSessionCacheService;
    @MockBean
    private RedisTemplate<String, Object> redisTemplate;
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
    private ClientIpResolver clientIpResolver;
    @MockBean(name = "webMvcTaskExecutor")
    private AsyncTaskExecutor webMvcTaskExecutor;

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
        when(chatRateLimitProperties.getStreamUserLimit()).thenReturn(100);
        when(chatRateLimitProperties.getStreamIpLimit()).thenReturn(100);
        when(chatRateLimitProperties.getStreamWindowSeconds()).thenReturn(60);
        when(chatRateLimitProperties.getMessageUserLimit()).thenReturn(100);
        when(chatRateLimitProperties.getMessageIpLimit()).thenReturn(100);
        when(chatRateLimitProperties.getMessageWindowSeconds()).thenReturn(60);
        when(chatRateLimitProperties.getSessionUserLimit()).thenReturn(100);
        when(chatRateLimitProperties.getSessionIpLimit()).thenReturn(100);
        when(chatRateLimitProperties.getSessionWindowSeconds()).thenReturn(60);
        when(chatRateLimitProperties.getDefaultUserLimit()).thenReturn(100);
        when(chatRateLimitProperties.getDefaultIpLimit()).thenReturn(100);
        when(chatRateLimitProperties.getDefaultWindowSeconds()).thenReturn(60);
        when(redisTokenBucketService.tryConsume(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    @Test
    void getAvailableModels_returnsConfiguredModels() throws Exception {
        when(billingProperties.getAvailableModels()).thenReturn(List.of("deepseek-chat", "gpt-4o-mini"));

        mockMvc.perform(get("/api/chat/models/options")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("deepseek-chat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("gpt-4o-mini")));
    }

    @Test
    void getSessions_returnsPageResult() throws Exception {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setChatId("chat-1");
        when(sessionService.getChatSessionsByUserId(eq(1L), eq(1), eq(20))).thenReturn(List.of(vo));
        when(sessionService.countChatSessionsByUserId(eq(1L))).thenReturn(1L);

        mockMvc.perform(get("/api/chat/sessions?page=1&size=20")
                        .header("Authorization", "Bearer token")
                        .requestAttr(RequestAttributeConst.CURRENT_USER, CURRENT_USER))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chat-1")));
    }

    @Test
    void createSession_returnsSuccess() throws Exception {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setChatId("chat-new");
        when(sessionService.createSession(eq(1L), any(), any(), any())).thenReturn(vo);

        mockMvc.perform(post("/api/chat/session")
                        .header("Authorization", "Bearer token")
                        .requestAttr(RequestAttributeConst.CURRENT_USER, CURRENT_USER))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chat-new")));
    }

    @Test
    void sendMessage_requiresRequestId() throws Exception {
        mockMvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .requestAttr(RequestAttributeConst.CURRENT_USER, CURRENT_USER)
                        .content("{\"chatId\":\"chat-1\",\"prompt\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("requestId is required")));
    }

    @Test
    void sendMessage_supportsBackwardCompatibleGroundedPayload() throws Exception {
        GroundedChatResponse grounded = GroundedChatResponse.builder()
                .content("Grounded answer")
                .citations(List.of(RagCitation.builder()
                        .docId("doc-1")
                        .title("distributed-systems.md")
                        .sourcePath("distributed/distributed-systems.md")
                        .excerpt("CAP theorem excerpt")
                        .build()))
                .grounding(GroundingAssessment.builder()
                        .status("grounded")
                        .groundingScore(0.91)
                        .evidenceCount(2)
                        .eligibleCitationCount(1)
                        .policyVersion("v1")
                        .build())
                .build();
        when(chatService.sendMessage(eq(1L), isNull(), eq("Explain CAP theorem"), isNull(), isNull(), eq("req-1"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(grounded);

        mockMvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer token")
                        .requestAttr(RequestAttributeConst.CURRENT_USER, CURRENT_USER)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "prompt", "Explain CAP theorem",
                                "requestId", "req-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"content\":\"Grounded answer\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"grounding\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"grounded\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"citations\"")));
    }

    @Test
    void getSessions_returnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isUnauthorized());
    }
}
