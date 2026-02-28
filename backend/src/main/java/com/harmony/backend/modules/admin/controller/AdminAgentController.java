package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.ai.agent.controller.response.AgentVO;
import com.harmony.backend.ai.agent.service.AgentService;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.ai.agent.controller.request.AgentUpsertRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/agents")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAgentController {

    private final AgentService agentService;

    @GetMapping
    public ApiResponse<PageResult<AgentVO>> listAgents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean requestPublic) {
        return ApiResponse.success(agentService.listAll(page, size, keyword, requestPublic));
    }

    @GetMapping("/{agentId}")
    public ApiResponse<AgentVO> getDetail(@PathVariable String agentId) {
        Long adminUserId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(agentService.getDetail(agentId, adminUserId, true));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAgents(@RequestParam(required = false) String keyword) {
        List<AgentVO> agents = agentService.listAll(1, 10000, keyword, null).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("agent_id,name,model,user_id,is_public,request_public,multi_agent,tools,created_at,updated_at\n");
        for (AgentVO a : agents) {
            sb.append(csv(a.getAgentId()))
              .append(',').append(csv(a.getName()))
              .append(',').append(csv(a.getModel()))
              .append(',').append(csv(a.getUserId()))
              .append(',').append(csv(a.getIsPublic()))
              .append(',').append(csv(a.getRequestPublic()))
              .append(',').append(csv(a.getMultiAgent()))
              .append(',').append(csv(a.getTools()))
              .append(',').append(csv(a.getCreatedAt()))
              .append(',').append(csv(a.getUpdatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=agents.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @PutMapping("/{agentId}")
    public ApiResponse<AgentVO> updateAgent(@PathVariable String agentId,
                                            @RequestBody AgentUpsertRequest request) {
        Long adminUserId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(agentService.update(agentId, adminUserId, true, request));
    }

    @DeleteMapping("/{agentId}")
    public ApiResponse<Boolean> deleteAgent(@PathVariable String agentId) {
        Long adminUserId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(agentService.delete(agentId, adminUserId, true));
    }

    private String csv(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace("\"", "\"\"");
        return "\"" + text + "\"";
    }

    private byte[] withBom(String text) {
        String bom = "\uFEFF";
        return (bom + text).getBytes(StandardCharsets.UTF_8);
    }
}
