package com.harmony.backend.ai.agent.controller;

import com.harmony.backend.ai.agent.controller.request.AgentUpsertRequest;
import com.harmony.backend.ai.agent.controller.response.AgentVO;
import com.harmony.backend.ai.agent.service.AgentService;
import com.harmony.backend.ai.tool.AgentToolDefinition;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.RequestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;
    private final AgentToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    @GetMapping("/tools")
    public ApiResponse<List<AgentToolDefinition>> listTools() {
        return ApiResponse.success(toolRegistry.listAll());
    }

    @PostMapping("/tools/execute")
    public ApiResponse<ToolExecutionResult> executeTool(@RequestBody ToolExecutionRequest request) {
        return ApiResponse.success(toolExecutor.execute(request));
    }

    @GetMapping("/public")
    public ApiResponse<PageResult<AgentVO>> listPublic(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(agentService.listPublic(page, size, keyword));
    }

    @GetMapping("/mine")
    public ApiResponse<PageResult<AgentVO>> listMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(agentService.listMine(userId, page, size));
    }

    @GetMapping("/{agentId}")
    public ApiResponse<AgentVO> getDetail(@PathVariable String agentId) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(agentService.getDetail(agentId, userId, isAdmin));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<AgentVO> create(@RequestBody AgentUpsertRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(agentService.create(userId, isAdmin, request));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create agent failed", e);
            return ApiResponse.error("Create agent failed");
        }
    }

    @PutMapping("/{agentId}")
    public ApiResponse<AgentVO> update(@PathVariable String agentId,
                                       @RequestBody AgentUpsertRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(agentService.update(agentId, userId, isAdmin, request));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Update agent failed: agentId={}", agentId, e);
            return ApiResponse.error("Update agent failed");
        }
    }

    @DeleteMapping("/{agentId}")
    public ApiResponse<Boolean> delete(@PathVariable String agentId) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(agentService.delete(agentId, userId, isAdmin));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Delete agent failed: agentId={}", agentId, e);
            return ApiResponse.error("Delete agent failed");
        }
    }
}
