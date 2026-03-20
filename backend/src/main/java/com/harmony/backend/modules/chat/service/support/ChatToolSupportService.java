package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatToolSupportService {

    private final AgentSkillRegistry skillRegistry;
    private final ChatSessionSupportService chatSessionSupportService;
    private final ToolFollowupService toolFollowupService;

    public String handleToolCallIfNeeded(Session session,
                                         List<LlmMessage> messages,
                                         String assistantContent,
                                         String model,
                                         String assistantMessageId) {
        return toolFollowupService.handleToolCallIfNeeded(session, messages, assistantContent, model, assistantMessageId,
                new ToolFollowupService.ToolPolicy() {
                    @Override
                    public String detectToolIntent(Session currentSession, String prompt) {
                        return ChatToolSupportService.this.detectToolIntent(currentSession, prompt);
                    }

                    @Override
                    public String buildToolInput(String toolKey, String prompt) {
                        return ChatToolSupportService.this.buildToolInput(toolKey, prompt);
                    }

                    @Override
                    public boolean isToolAllowed(Session currentSession, String toolKey) {
                        return ChatToolSupportService.this.isToolAllowed(currentSession, toolKey);
                    }

                    @Override
                    public List<String> getAllowedSkills(Session currentSession) {
                        return ChatToolSupportService.this.getAllowedSkills(currentSession);
                    }
                });
    }

    public boolean shouldBufferToolStream(Agent sessionAgent) {
        if (sessionAgent == null) {
            return false;
        }
        List<String> tools = skillRegistry.expandToolsForSkills(chatSessionSupportService.readAgentSkills(sessionAgent));
        return tools != null && !tools.isEmpty();
    }

    public List<String> getAllowedSkills(Session session) {
        if (session == null || session.getAgentId() == null || session.getAgentId().isBlank()) {
            return List.of();
        }
        Agent agent = chatSessionSupportService.resolveAgentEntity(session);
        return chatSessionSupportService.readAgentSkills(agent);
    }

    public String detectToolIntent(Session session, String prompt) {
        if (session == null || prompt == null) {
            return null;
        }
        String p = prompt.trim();
        if (p.isEmpty()) {
            return null;
        }
        boolean allowTime = isToolAllowed(session, "datetime");
        boolean allowWeb = isToolAllowed(session, "web_search");
        String lower = p.toLowerCase();
        if (allowTime && isTimeQuery(p, lower)) {
            return "datetime";
        }
        if (!allowTime && allowWeb && isTimeQuery(p, lower)) {
            return "web_search";
        }
        if (isSearchQuery(p, lower) && allowWeb) {
            return "web_search";
        }
        return null;
    }

    public String buildToolInput(String toolKey, String prompt) {
        if ("datetime".equalsIgnoreCase(toolKey)) {
            if (prompt.contains("北京") || prompt.contains("上海") || prompt.contains("中国") || prompt.contains("时间")) {
                return "Asia/Shanghai";
            }
            return "UTC";
        }
        if ("web_search".equalsIgnoreCase(toolKey) && isTimeQuery(prompt, prompt.toLowerCase())) {
            return "current time in Asia/Shanghai";
        }
        return prompt;
    }

    public boolean isToolAllowed(Session session, String toolKey) {
        if (session == null || toolKey == null || toolKey.isBlank()) {
            return false;
        }
        List<String> tools = skillRegistry.expandToolsForSkills(getAllowedSkills(session));
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        return tools.stream().anyMatch(t -> toolKey.equalsIgnoreCase(t));
    }

    private boolean isTimeQuery(String original, String lower) {
        return lower.contains("time")
                || lower.contains("now")
                || original.contains("北京时间")
                || original.contains("上海时间")
                || original.contains("中国时间")
                || original.contains("几点")
                || original.contains("时间");
    }

    private boolean isSearchQuery(String original, String lower) {
        return original.contains("搜索")
                || original.contains("查询")
                || original.contains("查一下")
                || lower.contains("search")
                || lower.contains("news")
                || original.contains("资料")
                || original.contains("消息");
    }
}
