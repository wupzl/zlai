package com.harmony.backend.modules.chat.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.model.TeamAgentConfig;
import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.AgentMapper;
import com.harmony.backend.common.mapper.GPTMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.modules.chat.prompt.ChatPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatSessionSupportService {

    private final SessionMapper sessionMapper;
    private final AgentMapper agentMapper;
    private final GPTMapper gptMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final AgentSkillRegistry skillRegistry;
    private final ChatPromptService chatPromptService;

    public Session getSession(String chatId) {
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .eq(Session::getChatId, chatId)
                .eq(Session::getIsDeleted, false));
        if (session == null) {
            throw new BusinessException(404, "Chat session not found");
        }
        return session;
    }

    public void validateGptMatch(Session session, String gptId) {
        if (gptId == null || gptId.isBlank()) {
            return;
        }
        if (session.getGptId() != null && !session.getGptId().equals(gptId)) {
            throw new BusinessException(400, "GPT does not match session");
        }
    }

    public void validateAgentMatch(Session session, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        if (session.getAgentId() != null && !session.getAgentId().equals(agentId)) {
            throw new BusinessException(400, "Agent does not match session");
        }
    }

    public String resolveSystemPrompt(Session session) {
        if (session.getSystemPrompt() != null && !session.getSystemPrompt().isBlank()) {
            return session.getSystemPrompt();
        }
        if ((session.getGptId() == null || session.getGptId().isBlank())
                && (session.getAgentId() == null || session.getAgentId().isBlank())) {
            return null;
        }
        Gpt gpt = null;
        Agent agent = null;
        if (session.getGptId() != null && !session.getGptId().isBlank()) {
            gpt = gptMapper.selectOne(new LambdaQueryWrapper<Gpt>()
                    .eq(Gpt::getGptId, session.getGptId())
                    .eq(Gpt::getIsDeleted, false));
        }
        if (session.getAgentId() != null && !session.getAgentId().isBlank()) {
            agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getAgentId, session.getAgentId())
                    .eq(Agent::getIsDeleted, false));
        }
        String prompt = chatPromptService.buildSystemPrompt(gpt, agent);
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        session.setSystemPrompt(prompt);
        sessionMapper.updateById(session);
        return prompt;
    }

    public Gpt resolveGptForSession(Long userId, String gptId) {
        if (gptId == null || gptId.isBlank()) {
            return null;
        }
        Gpt gpt = gptMapper.selectOne(new LambdaQueryWrapper<Gpt>()
                .eq(Gpt::getGptId, gptId)
                .eq(Gpt::getIsDeleted, false));
        if (gpt == null) {
            throw new BusinessException(404, "GPT not found");
        }
        if (Boolean.TRUE.equals(gpt.getIsPublic())) {
            return gpt;
        }
        if (gpt.getUserId() != null && gpt.getUserId().equals(userId)) {
            return gpt;
        }
        if (isAdmin(userId)) {
            return gpt;
        }
        throw new BusinessException(403, "GPT not accessible");
    }

    public Agent resolveAgentForSession(Long userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        Agent agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, agentId)
                .eq(Agent::getIsDeleted, false));
        if (agent == null) {
            throw new BusinessException(404, "Agent not found");
        }
        if (Boolean.TRUE.equals(agent.getIsPublic())) {
            return agent;
        }
        if (agent.getUserId() != null && agent.getUserId().equals(userId)) {
            return agent;
        }
        if (isAdmin(userId)) {
            return agent;
        }
        throw new BusinessException(403, "Agent not accessible");
    }

    public Agent resolveAgentEntity(Session session) {
        if (session == null || session.getAgentId() == null || session.getAgentId().isBlank()) {
            return null;
        }
        return agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, session.getAgentId())
                .eq(Agent::getIsDeleted, false));
    }

    public List<TeamAgentRuntime> resolveTeamAgents(Agent manager, Long userId) {
        if (manager == null) {
            return List.of();
        }
        try {
            List<TeamAgentConfig> configs = List.of();
            if (manager.getTeamConfig() != null && !manager.getTeamConfig().isBlank()) {
                configs = objectMapper.readValue(manager.getTeamConfig(), new TypeReference<List<TeamAgentConfig>>() {});
            }
            List<String> ids;
            if (configs != null && !configs.isEmpty()) {
                ids = new ArrayList<>();
                for (TeamAgentConfig cfg : configs) {
                    if (cfg != null && cfg.getAgentId() != null) {
                        ids.add(cfg.getAgentId());
                    }
                }
            } else {
                if (manager.getTeamAgentIds() == null || manager.getTeamAgentIds().isBlank()) {
                    return List.of();
                }
                ids = objectMapper.readValue(manager.getTeamAgentIds(), new TypeReference<List<String>>() {});
            }
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<Agent> agents = agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                    .eq(Agent::getIsDeleted, false)
                    .in(Agent::getAgentId, ids));
            if (agents == null || agents.isEmpty()) {
                return List.of();
            }
            List<TeamAgentRuntime> allowed = new ArrayList<>();
            for (Agent agent : agents) {
                if (agent == null) {
                    continue;
                }
                if (Boolean.TRUE.equals(agent.getIsPublic()) || (userId != null && userId.equals(agent.getUserId())) || isAdmin(userId)) {
                    allowed.add(buildRuntime(agent, configs));
                }
            }
            if (allowed.size() < ids.size()) {
                log.warn("Some team agents are filtered by permission or missing. managerAgentId={}, requestedIds={}, resolvedIds={}, requesterUserId={}",
                        manager.getAgentId(), ids, allowed.stream()
                                .map(a -> a.getAgent() != null ? a.getAgent().getAgentId() : "null").toList(), userId);
            }
            return allowed;
        } catch (Exception e) {
            log.warn("Resolve team agents failed. managerAgentId={}, requesterUserId={}, error={}",
                    manager.getAgentId(), userId, e.getMessage());
            return List.of();
        }
    }

    public boolean isMultiAgentEnabled(Agent agent) {
        return agent != null && Boolean.TRUE.equals(agent.getMultiAgent());
    }

    public void incrementGptUsage(Gpt gpt) {
        if (gpt == null || gpt.getId() == null) {
            return;
        }
        Gpt update = new Gpt();
        update.setId(gpt.getId());
        Integer usage = gpt.getUsageCount() == null ? 0 : gpt.getUsageCount();
        update.setUsageCount(usage + 1);
        gptMapper.updateById(update);
    }

    public String resolveModelForNewSession(String model, Gpt gpt, Agent agent) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (gpt != null && gpt.getModel() != null && !gpt.getModel().isBlank()) {
            return gpt.getModel();
        }
        if (agent != null && agent.getModel() != null && !agent.getModel().isBlank()) {
            return agent.getModel();
        }
        return "deepseek-chat";
    }

    public String resolveModelForSession(Session session, String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if (session.getModel() != null && !session.getModel().isBlank()) {
            return session.getModel();
        }
        return "deepseek-chat";
    }

    public boolean isRagRequired(Session session, Boolean useRag) {
        boolean sessionEnabled = session != null && Boolean.TRUE.equals(session.getRagEnabled());
        if (!sessionEnabled) {
            return false;
        }
        return useRag == null || Boolean.TRUE.equals(useRag);
    }

    public void applyToolModelUpdate(Session session, String toolModel) {
        if (session == null || toolModel == null || toolModel.isBlank()) {
            return;
        }
        String trimmed = toolModel.trim();
        if (trimmed.equals(session.getToolModel())) {
            return;
        }
        Session update = new Session();
        update.setId(session.getId());
        update.setToolModel(trimmed);
        sessionMapper.updateById(update);
        session.setToolModel(trimmed);
    }

    public List<String> readAgentSkills(Agent agent) {
        if (agent == null || agent.getSkills() == null || agent.getSkills().isBlank()) {
            return List.of();
        }
        try {
            List<String> skills = objectMapper.readValue(agent.getSkills(), new TypeReference<List<String>>() {});
            return skills == null ? List.of() : skills;
        } catch (Exception e) {
            return List.of();
        }
    }

    private TeamAgentRuntime buildRuntime(Agent agent, List<TeamAgentConfig> configs) {
        TeamAgentRuntime runtime = new TeamAgentRuntime();
        runtime.setAgent(agent);
        List<String> skills = null;
        if (configs != null) {
            for (TeamAgentConfig cfg : configs) {
                if (cfg != null && cfg.getAgentId() != null && cfg.getAgentId().equals(agent.getAgentId())) {
                    runtime.setRole(cfg.getRole());
                    skills = cfg.getSkills();
                    break;
                }
            }
        }
        if (skills == null || skills.isEmpty()) {
            skills = readAgentSkills(agent);
        }
        runtime.setSkills(skills);
        runtime.setTools(skillRegistry.expandToolsForSkills(skills));
        return runtime;
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        com.harmony.backend.common.entity.User user = userMapper.selectById(userId);
        return user != null && user.isAdmin();
    }
}
