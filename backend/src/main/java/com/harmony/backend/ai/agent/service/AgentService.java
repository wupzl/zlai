package com.harmony.backend.ai.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.ai.agent.controller.request.AgentUpsertRequest;
import com.harmony.backend.ai.agent.controller.response.AgentVO;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.response.PageResult;

import java.util.List;

public interface AgentService extends IService<Agent> {
    List<String> validateTools(List<String> tools);

    PageResult<AgentVO> listMine(Long userId, int page, int size);

    PageResult<AgentVO> listPublic(int page, int size, String keyword);

    PageResult<AgentVO> listAll(int page, int size, String keyword);

    AgentVO getDetail(String agentId, Long userId, boolean isAdmin);

    AgentVO create(Long userId, boolean isAdmin, AgentUpsertRequest request);

    AgentVO update(String agentId, Long userId, boolean isAdmin, AgentUpsertRequest request);

    boolean delete(String agentId, Long userId, boolean isAdmin);
}
