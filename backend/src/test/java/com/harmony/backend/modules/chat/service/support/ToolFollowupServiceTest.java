package com.harmony.backend.modules.chat.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.ai.skill.SkillPlan;
import com.harmony.backend.ai.skill.SkillPlanner;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.prompt.ChatPromptService;
import com.harmony.backend.modules.chat.service.BillingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolFollowupServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
    private final SkillExecutor skillExecutor = mock(SkillExecutor.class);
    private final SkillPlanner skillPlanner = mock(SkillPlanner.class);
    private final AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
    private final LlmAdapterRegistry adapterRegistry = mock(LlmAdapterRegistry.class);
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final ChatPromptService chatPromptService = mock(ChatPromptService.class);
    private final BillingService billingService = mock(BillingService.class);

    private final ToolFollowupService service = new ToolFollowupService(
            objectMapper,
            toolExecutor,
            skillExecutor,
            skillPlanner,
            toolRegistry,
            adapterRegistry,
            agentMemoryService,
            chatPromptService,
            billingService
    );

    @Test
    void handleToolCallIfNeeded_should_keep_normal_chat_on_mainline() {
        ToolFollowupService.ToolPolicy toolPolicy = mock(ToolFollowupService.ToolPolicy.class);
        Session session = Session.builder().userId(7L).chatId("chat-1").build();
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Explain CAP theorem briefly."));
        String assistantContent = "CAP theorem discusses consistency, availability, and partition tolerance.";

        when(toolPolicy.detectToolIntent(session, "Explain CAP theorem briefly.")).thenReturn(null);

        String result = service.handleToolCallIfNeeded(
                session,
                messages,
                assistantContent,
                "deepseek-chat",
                "msg-1",
                toolPolicy
        );

        assertEquals(assistantContent, result);
        verify(skillPlanner, never()).plan(any(), any(), any(), any());
        verify(skillExecutor, never()).execute(any());
        verify(toolExecutor, never()).execute(any());
        verify(billingService, never()).recordToolConsumption(any(), any(), any(), any(), any());
        verify(agentMemoryService, never()).recordSkillExecution(any(), any(), any(), any(), any(), any());
        verify(adapterRegistry, never()).getAdapter(any());
    }

    @Test
    void handleToolCallIfNeeded_should_execute_skill_followup_and_record_hooks() {
        ToolFollowupService.ToolPolicy toolPolicy = mock(ToolFollowupService.ToolPolicy.class);
        LlmAdapter adapter = mock(LlmAdapter.class);
        Session session = Session.builder()
                .userId(42L)
                .chatId("chat-42")
                .toolModel("skill-model")
                .build();
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Summarize the knowledge base note."));
        String assistantContent = "{\"skill\":\"kb_summary\",\"input\":\"{\\\"query\\\":\\\"CAP theorem\\\"}\"}";
        SkillPlan plan = new SkillPlan("kb_summary", List.of(), "{\"query\":\"CAP theorem\"}", "matched allowed skill");
        SkillExecutionResult executionResult = SkillExecutionResult.ok(
                "CAP theorem summary result",
                "skill-model",
                33,
                12,
                List.of("rag_knowledge_search")
        );

        when(toolPolicy.getAllowedSkills(session)).thenReturn(List.of("kb_summary"));
        when(skillPlanner.plan(
                "Summarize the knowledge base note.",
                "kb_summary",
                "{\"query\":\"CAP theorem\"}",
                List.of("kb_summary")
        )).thenReturn(plan);
        when(skillExecutor.execute(new SkillExecutionRequest("kb_summary", "{\"query\":\"CAP theorem\"}", "skill-model")))
                .thenReturn(executionResult);
        when(chatPromptService.buildToolFollowupUserMessage("CAP theorem summary result", false, ""))
                .thenReturn("tool followup prompt");
        when(adapterRegistry.getAdapter("deepseek-chat")).thenReturn(adapter);
        when(adapter.chat(any(), eq("deepseek-chat"))).thenReturn("final grounded answer");

        String result = service.handleToolCallIfNeeded(
                session,
                messages,
                assistantContent,
                "deepseek-chat",
                "msg-2",
                toolPolicy
        );

        assertEquals("final grounded answer", result);
        verify(skillPlanner).plan(
                "Summarize the knowledge base note.",
                "kb_summary",
                "{\"query\":\"CAP theorem\"}",
                List.of("kb_summary")
        );
        verify(skillExecutor).execute(new SkillExecutionRequest("kb_summary", "{\"query\":\"CAP theorem\"}", "skill-model"));
        verify(billingService).recordToolConsumption(session, "msg-2", "skill-model", 33, 12);
        verify(agentMemoryService).recordSkillExecution(
                42L,
                "chat-42",
                "kb_summary",
                "{\"query\":\"CAP theorem\"}",
                "CAP theorem summary result",
                "msg-2"
        );
        verify(adapterRegistry).getAdapter("deepseek-chat");
        verify(adapter).chat(any(), eq("deepseek-chat"));
    }

    @Test
    void handleToolCallIfNeeded_should_fallback_when_skill_is_disallowed_or_unresolved() {
        ToolFollowupService.ToolPolicy toolPolicy = mock(ToolFollowupService.ToolPolicy.class);
        LlmAdapter adapter = mock(LlmAdapter.class);
        Session session = Session.builder().userId(9L).chatId("chat-9").build();
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Use premium skill please."));
        String assistantContent = "{\"skill\":\"premium_skill\",\"input\":\"run it\"}";

        when(toolPolicy.getAllowedSkills(session)).thenReturn(List.of("basic_skill"));
        when(skillPlanner.plan("Use premium skill please.", "premium_skill", "run it", List.of("basic_skill")))
                .thenReturn(new SkillPlan(null, List.of(), "run it", "requested skill is not allowed"));
        when(adapterRegistry.getAdapter("deepseek-chat")).thenReturn(adapter);
        when(adapter.chat(any(), eq("deepseek-chat"))).thenReturn("fallback direct answer");

        String result = service.handleToolCallIfNeeded(
                session,
                messages,
                assistantContent,
                "deepseek-chat",
                "msg-3",
                toolPolicy
        );

        assertEquals("fallback direct answer", result);
        verify(skillPlanner).plan("Use premium skill please.", "premium_skill", "run it", List.of("basic_skill"));
        verify(skillExecutor, never()).execute(any());
        verify(billingService, never()).recordToolConsumption(any(), any(), any(), any(), any());
        verify(agentMemoryService, never()).recordSkillExecution(any(), any(), any(), any(), any(), any());
        verify(adapterRegistry).getAdapter("deepseek-chat");
        verify(adapter).chat(any(), eq("deepseek-chat"));
    }

    @Test
    void handleToolCallIfNeeded_should_surface_skill_execution_failure_without_side_effect_hooks() {
        ToolFollowupService.ToolPolicy toolPolicy = mock(ToolFollowupService.ToolPolicy.class);
        Session session = Session.builder()
                .userId(77L)
                .chatId("chat-77")
                .toolModel("skill-model")
                .build();
        List<LlmMessage> messages = List.of(new LlmMessage("user", "Run the skill and summarize."));
        String assistantContent = "{\"skill\":\"kb_summary\",\"input\":\"{\\\"query\\\":\\\"failure case\\\"}\"}";
        SkillPlan plan = new SkillPlan("kb_summary", List.of(), "{\"query\":\"failure case\"}", "matched allowed skill");

        when(toolPolicy.getAllowedSkills(session)).thenReturn(List.of("kb_summary"));
        when(skillPlanner.plan(
                "Run the skill and summarize.",
                "kb_summary",
                "{\"query\":\"failure case\"}",
                List.of("kb_summary")
        )).thenReturn(plan);
        when(skillExecutor.execute(new SkillExecutionRequest("kb_summary", "{\"query\":\"failure case\"}", "skill-model")))
                .thenReturn(SkillExecutionResult.fail("downstream tool timeout"));

        String result = service.handleToolCallIfNeeded(
                session,
                messages,
                assistantContent,
                "deepseek-chat",
                "msg-4",
                toolPolicy
        );

        assertEquals("Skill execution failed: downstream tool timeout", result);
        verify(skillPlanner).plan(
                "Run the skill and summarize.",
                "kb_summary",
                "{\"query\":\"failure case\"}",
                List.of("kb_summary")
        );
        verify(skillExecutor).execute(new SkillExecutionRequest("kb_summary", "{\"query\":\"failure case\"}", "skill-model"));
        verify(billingService, never()).recordToolConsumption(any(), any(), any(), any(), any());
        verify(agentMemoryService, never()).recordSkillExecution(any(), any(), any(), any(), any(), any());
        verify(adapterRegistry, never()).getAdapter(any());
    }
}
