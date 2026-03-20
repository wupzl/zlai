package com.harmony.backend.modules.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.model.SkillMarkdownImportRequest;
import com.harmony.backend.ai.skill.model.SkillUpsertRequest;
import com.harmony.backend.ai.skill.model.SkillVO;
import com.harmony.backend.ai.skill.service.AgentSkillCatalogService;
import com.harmony.backend.ai.skill.service.SkillMarkdownImportService;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.util.RequestUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/skills")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSkillController {

    private final AgentSkillCatalogService agentSkillCatalogService;
    private final SkillMarkdownImportService skillMarkdownImportService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<SkillVO>> listSkills() {
        return ApiResponse.success(agentSkillCatalogService.listManagedSkills());
    }

    @PostMapping
    public ApiResponse<SkillVO> createSkill(@RequestBody SkillUpsertRequest request) {
        return ApiResponse.success(agentSkillCatalogService.create(request, RequestUtils.getCurrentUserId()));
    }

    @PostMapping("/import-markdown")
    public ApiResponse<SkillVO> importMarkdown(@RequestBody SkillMarkdownImportRequest request) {
        return ApiResponse.success(skillMarkdownImportService.importMarkdown(request, RequestUtils.getCurrentUserId()));
    }

    @PostMapping(value = "/import-markdown-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SkillVO> importMarkdownFile(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(value = "sourceName", required = false) String sourceName,
                                                   @RequestParam(value = "defaultToolKeys", required = false) List<String> defaultToolKeys,
                                                   @RequestParam(value = "defaultExecutionMode", required = false) String defaultExecutionMode,
                                                   @RequestParam(value = "overwriteExisting", required = false) Boolean overwriteExisting,
                                                   @RequestParam(value = "enabled", required = false) Boolean enabled,
                                                   @RequestParam(value = "toolAliasesJson", required = false) String toolAliasesJson) throws Exception {
        SkillMarkdownImportRequest request = new SkillMarkdownImportRequest();
        request.setMarkdownContent(new String(file.getBytes(), StandardCharsets.UTF_8));
        request.setSourceName(sourceName != null && !sourceName.isBlank() ? sourceName : file.getOriginalFilename());
        request.setDefaultToolKeys(defaultToolKeys);
        request.setDefaultExecutionMode(defaultExecutionMode);
        request.setOverwriteExisting(overwriteExisting);
        request.setEnabled(enabled);
        if (toolAliasesJson != null && !toolAliasesJson.isBlank()) {
            request.setToolAliases(objectMapper.readValue(toolAliasesJson, new TypeReference<Map<String, String>>() {}));
        }
        return ApiResponse.success(skillMarkdownImportService.importMarkdown(request, RequestUtils.getCurrentUserId()));
    }

    @PutMapping("/{skillKey}")
    public ApiResponse<SkillVO> updateSkill(@PathVariable String skillKey,
                                            @RequestBody SkillUpsertRequest request) {
        return ApiResponse.success(agentSkillCatalogService.update(skillKey, request, RequestUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{skillKey}")
    public ApiResponse<Boolean> deleteSkill(@PathVariable String skillKey) {
        return ApiResponse.success(agentSkillCatalogService.delete(skillKey));
    }
}
