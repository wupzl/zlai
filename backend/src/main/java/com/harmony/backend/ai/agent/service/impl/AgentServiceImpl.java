package com.harmony.backend.ai.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.agent.controller.request.AgentUpsertRequest;
import com.harmony.backend.ai.agent.controller.response.AgentVO;
import com.harmony.backend.ai.agent.model.TeamAgentConfig;
import com.harmony.backend.ai.agent.service.AgentService;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.AgentMapper;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.SessionMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl extends ServiceImpl<AgentMapper, Agent> implements AgentService {

    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

    @Override
    public List<String> validateTools(List<String> tools) {
        if (tools == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String tool : tools) {
            if (!StringUtils.hasText(tool)) {
                continue;
            }
            String key = tool.trim();
            if (!toolRegistry.isValidKey(key)) {
                throw new BusinessException(400, "Invalid tool: " + key);
            }
            normalized.add(key);
        }
        return normalized;
    }

    @Override
    public PageResult<AgentVO> listMine(Long userId, int page, int size) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Page<Agent> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Agent> query = new LambdaQueryWrapper<>();
        query.eq(Agent::getIsDeleted, false)
                .eq(Agent::getUserId, userId)
                .orderByDesc(Agent::getCreatedAt);
        Page<Agent> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return toVoPage(result);
    }

    @Override
    public PageResult<AgentVO> listPublic(int page, int size, String keyword) {
        Page<Agent> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Agent> query = new LambdaQueryWrapper<>();
        query.eq(Agent::getIsDeleted, false)
                .eq(Agent::getIsPublic, true);
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Agent::getName, keyword)
                    .or()
                    .like(Agent::getDescription, keyword));
        }
        query.orderByDesc(Agent::getCreatedAt);
        Page<Agent> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return toVoPage(result);
    }

    @Override
    public PageResult<AgentVO> listAll(int page, int size, String keyword, Boolean requestPublic) {
        Page<Agent> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<Agent> query = new LambdaQueryWrapper<>();
        query.eq(Agent::getIsDeleted, false);
        if (requestPublic != null) {
            query.eq(Agent::getRequestPublic, requestPublic);
        }
        if (StringUtils.hasText(keyword)) {
            query.and(q -> q.like(Agent::getName, keyword)
                    .or()
                    .like(Agent::getDescription, keyword));
        }
        query.orderByDesc(Agent::getCreatedAt);
        Page<Agent> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return toVoPage(result);
    }

    @Override
    public AgentVO getDetail(String agentId, Long userId, boolean isAdmin) {
        Agent agent = findByAgentId(agentId);
        if (agent == null) {
            throw new BusinessException(404, "Agent not found");
        }
        if (Boolean.TRUE.equals(agent.getIsPublic())) {
            return toVo(agent);
        }
        if (isAdmin || (userId != null && userId.equals(agent.getUserId()))) {
            return toVo(agent);
        }
        throw new BusinessException(404, "Agent not found");
    }

    @Override
    public AgentVO create(Long userId, boolean isAdmin, AgentUpsertRequest request) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        validateRequest(request);
        List<String> tools = validateTools(request.getTools());
        TeamValidationResult teamResult = validateTeamAgents(request.getTeamConfigs(), request.getTeamAgentIds(), userId, isAdmin);
        Agent agent = new Agent();
        agent.setAgentId(UUID.randomUUID().toString());
        agent.setName(request.getName().trim());
        agent.setDescription(trimOrNull(request.getDescription()));
        agent.setInstructions(trimOrNull(request.getInstructions()));
        agent.setModel(resolveModel(request.getModel()));
        agent.setToolModel(trimOrNull(request.getToolModel()));
        agent.setUserId(userId);
        agent.setTools(writeTools(tools));
        agent.setMultiAgent(Boolean.TRUE.equals(request.getMultiAgent()));
        agent.setTeamAgentIds(writeTeamAgents(teamResult.teamIds));
        agent.setTeamConfig(writeTeamConfig(teamResult.teamConfigs));
        boolean requestPublic = Boolean.TRUE.equals(request.getRequestPublic());
        agent.setIsPublic(isAdmin && requestPublic);
        agent.setRequestPublic(!isAdmin && requestPublic);

        baseMapper.insert(agent);
        return toVo(agent, tools);
    }

    @Override
    public AgentVO update(String agentId, Long userId, boolean isAdmin, AgentUpsertRequest request) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Agent agent = findByAgentId(agentId);
        if (agent == null) {
            throw new BusinessException(404, "Agent not found");
        }
        if (!isAdmin && !userId.equals(agent.getUserId())) {
            throw new BusinessException(403, "Forbidden");
        }
        if (!isAdmin && Boolean.TRUE.equals(agent.getIsPublic())) {
            throw new BusinessException(403, "Public agent can only be updated by admin");
        }
        boolean updated = applyUpdates(agent, request);
        if (request.getTools() != null) {
            List<String> tools = validateTools(request.getTools());
            agent.setTools(writeTools(tools));
            updated = true;
        }
        if (request.getTeamAgentIds() != null || request.getTeamConfigs() != null) {
            TeamValidationResult teamResult = validateTeamAgents(request.getTeamConfigs(), request.getTeamAgentIds(), userId, isAdmin);
            agent.setTeamAgentIds(writeTeamAgents(teamResult.teamIds));
            agent.setTeamConfig(writeTeamConfig(teamResult.teamConfigs));
            updated = true;
        }
        if (request.getMultiAgent() != null && !Boolean.TRUE.equals(request.getMultiAgent())) {
            agent.setTeamAgentIds(writeTeamAgents(List.of()));
            agent.setTeamConfig(writeTeamConfig(List.of()));
            updated = true;
        }
        if (request.getRequestPublic() != null && isAdmin) {
            boolean approve = Boolean.TRUE.equals(request.getRequestPublic());
            agent.setIsPublic(approve);
            agent.setRequestPublic(false);
        } else if (request.getRequestPublic() != null) {
            agent.setIsPublic(false);
            agent.setRequestPublic(Boolean.TRUE.equals(request.getRequestPublic()));
        }
        if (updated) {
            baseMapper.updateById(agent);
        }
        return toVo(agent);
    }

    @Override
    public boolean delete(String agentId, Long userId, boolean isAdmin) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        Agent agent = findByAgentId(agentId);
        if (agent == null) {
            throw new BusinessException(404, "Agent not found");
        }
        if (!isAdmin && !userId.equals(agent.getUserId())) {
            throw new BusinessException(403, "Forbidden");
        }
        if (!isAdmin && Boolean.TRUE.equals(agent.getIsPublic())) {
            throw new BusinessException(403, "Public agent can only be deleted by admin");
        }
        int rows = baseMapper.delete(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, agentId));
        boolean ok = rows > 0;
        if (ok) {
            baseMapper.update(
                    null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Agent>()
                            .eq(Agent::getAgentId, agentId)
                            .set(Agent::getIsPublic, false)
                            .set(Agent::getRequestPublic, false)
                            .set(Agent::getUpdatedAt, LocalDateTime.now())
            );
        }
        if (ok) {
            cleanupAgentSessions(agentId);
        }
        return ok;
    }

    private void cleanupAgentSessions(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getAgentId, agentId)
                .eq(Session::getIsDeleted, false));
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        List<String> chatIds = sessions.stream()
                .map(Session::getChatId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        sessionMapper.update(
                null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Session>()
                        .eq(Session::getAgentId, agentId)
                        .eq(Session::getIsDeleted, false)
                        .set(Session::getIsDeleted, true)
                        .set(Session::getUpdatedAt, LocalDateTime.now())
        );
        if (!chatIds.isEmpty()) {
            messageMapper.update(
                    null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                            .in(Message::getChatId, chatIds)
                            .set(Message::getIsDeleted, true)
                            .set(Message::getUpdatedAt, LocalDateTime.now())
            );
        }
    }

    private void validateRequest(AgentUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.getName())) {
            throw new BusinessException(400, "Agent name is required");
        }
        if (!StringUtils.hasText(request.getInstructions())) {
            throw new BusinessException(400, "Agent instructions are required");
        }
    }

    private boolean applyUpdates(Agent agent, AgentUpsertRequest request) {
        boolean updated = false;
        if (StringUtils.hasText(request.getName())) {
            agent.setName(request.getName().trim());
            updated = true;
        }
        if (request.getDescription() != null) {
            agent.setDescription(trimOrNull(request.getDescription()));
            updated = true;
        }
        if (request.getInstructions() != null) {
            agent.setInstructions(trimOrNull(request.getInstructions()));
            updated = true;
        }
        if (request.getMultiAgent() != null) {
            agent.setMultiAgent(Boolean.TRUE.equals(request.getMultiAgent()));
            updated = true;
        }
        if (request.getModel() != null) {
            agent.setModel(resolveModel(request.getModel()));
            updated = true;
        }
        if (request.getToolModel() != null) {
            agent.setToolModel(trimOrNull(request.getToolModel()));
            updated = true;
        }
        return updated;
    }

    private String resolveModel(String model) {
        if (!StringUtils.hasText(model)) {
            return "deepseek-chat";
        }
        return model.trim();
    }

    private String trimOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Agent findByAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return null;
        }
        return baseMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getAgentId, agentId)
                .eq(Agent::getIsDeleted, false));
    }

    private PageResult<AgentVO> toVoPage(Page<Agent> page) {
        PageResult<Agent> pageResult = PageResultUtils.from(page);
        PageResult<AgentVO> voResult = new PageResult<>();
        voResult.setTotalElements(pageResult.getTotalElements());
        voResult.setTotalPages(pageResult.getTotalPages());
        voResult.setPageNumber(pageResult.getPageNumber());
        voResult.setPageSize(pageResult.getPageSize());
        if (pageResult.getContent() == null) {
            voResult.setContent(List.of());
            return voResult;
        }
        voResult.setContent(pageResult.getContent().stream()
                .map(this::toVo)
                .collect(Collectors.toList()));
        return voResult;
    }

    private AgentVO toVo(Agent agent) {
        return toVo(agent, readTools(agent.getTools()));
    }

    private AgentVO toVo(Agent agent, List<String> tools) {
        AgentVO vo = new AgentVO();
        vo.setAgentId(agent.getAgentId());
        vo.setName(agent.getName());
        vo.setDescription(agent.getDescription());
        vo.setInstructions(agent.getInstructions());
        vo.setModel(agent.getModel());
        vo.setToolModel(agent.getToolModel());
        vo.setUserId(agent.getUserId());
        vo.setTools(tools);
        vo.setMultiAgent(Boolean.TRUE.equals(agent.getMultiAgent()));
        vo.setTeamAgentIds(readTeamAgents(agent.getTeamAgentIds()));
        vo.setTeamConfigs(readTeamConfig(agent.getTeamConfig()));
        vo.setIsPublic(agent.getIsPublic());
        vo.setRequestPublic(agent.getRequestPublic());
        vo.setCreatedAt(agent.getCreatedAt());
        vo.setUpdatedAt(agent.getUpdatedAt());
        return vo;
    }

    private String writeTools(List<String> tools) {
        try {
            return objectMapper.writeValueAsString(tools == null ? List.of() : tools);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize tools failed");
        }
    }

    private List<String> readTools(String toolsJson) {
        if (!StringUtils.hasText(toolsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(toolsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private TeamValidationResult validateTeamAgents(List<TeamAgentConfig> configs,
                                                    List<String> teamAgentIds,
                                                    Long userId,
                                                    boolean isAdmin) {
        List<String> normalized;
        List<TeamAgentConfig> normalizedConfigs = new ArrayList<>();
        if (configs != null && !configs.isEmpty()) {
            normalized = new ArrayList<>();
            for (TeamAgentConfig cfg : configs) {
                if (cfg == null || !StringUtils.hasText(cfg.getAgentId())) {
                    continue;
                }
                String id = cfg.getAgentId().trim();
                normalized.add(id);
                TeamAgentConfig copy = new TeamAgentConfig();
                copy.setAgentId(id);
                copy.setRole(trimOrNull(cfg.getRole()));
                copy.setTools(List.of());
                normalizedConfigs.add(copy);
            }
        } else if (teamAgentIds != null) {
            normalized = teamAgentIds.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .collect(Collectors.toList());
            for (String id : normalized) {
                TeamAgentConfig cfg = new TeamAgentConfig();
                cfg.setAgentId(id);
                normalizedConfigs.add(cfg);
            }
        } else {
            return new TeamValidationResult(List.of(), List.of());
        }
        if (normalized.isEmpty()) {
            return new TeamValidationResult(List.of(), List.of());
        }
        List<Agent> agents = baseMapper.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getIsDeleted, false)
                .in(Agent::getAgentId, normalized));
        if (agents.size() != normalized.size()) {
            throw new BusinessException(400, "Invalid team agent ids");
        }
        for (Agent agent : agents) {
            if (Boolean.TRUE.equals(agent.getIsPublic())) {
                continue;
            }
            if (isAdmin) {
                continue;
            }
            if (userId != null && userId.equals(agent.getUserId())) {
                continue;
            }
            throw new BusinessException(403, "Team agent not accessible: " + agent.getAgentId());
        }
        return new TeamValidationResult(normalized, normalizedConfigs);
    }

    private String writeTeamAgents(List<String> teamAgentIds) {
        try {
            return objectMapper.writeValueAsString(teamAgentIds == null ? List.of() : teamAgentIds);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize team agents failed");
        }
    }

    private List<String> readTeamAgents(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeTeamConfig(List<TeamAgentConfig> configs) {
        try {
            return objectMapper.writeValueAsString(configs == null ? List.of() : configs);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize team config failed");
        }
    }

    private List<TeamAgentConfig> readTeamConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<TeamAgentConfig>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static class TeamValidationResult {
        private final List<String> teamIds;
        private final List<TeamAgentConfig> teamConfigs;

        private TeamValidationResult(List<String> teamIds, List<TeamAgentConfig> teamConfigs) {
            this.teamIds = teamIds;
            this.teamConfigs = teamConfigs;
        }
    }
}
