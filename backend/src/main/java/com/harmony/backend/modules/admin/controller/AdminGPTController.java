package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.admin.service.AdminGptService;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
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
@RequestMapping("/api/admin/gpts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGPTController {

    private final AdminGptService gptService;

    @GetMapping
    public ApiResponse<PageResult<Gpt>> listGpts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean requestPublic) {
        return ApiResponse.success(gptService.listGpts(page, size, keyword, requestPublic));
    }

    @GetMapping("/{id}")
    public ApiResponse<Gpt> getDetail(@PathVariable Long id) {
        return ApiResponse.success(gptService.getDetail(id));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportGpts(@RequestParam(required = false) String keyword) {
        List<Gpt> gpts = gptService.listGpts(1, 10000, keyword, null).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("id,gpt_id,name,category,model,user_id,is_public,request_public,usage_count,created_at,updated_at\n");
        for (Gpt g : gpts) {
            sb.append(csv(g.getId()))
              .append(',').append(csv(g.getGptId()))
              .append(',').append(csv(g.getName()))
              .append(',').append(csv(g.getCategory()))
              .append(',').append(csv(g.getModel()))
              .append(',').append(csv(g.getUserId()))
              .append(',').append(csv(g.getIsPublic()))
              .append(',').append(csv(g.getRequestPublic()))
              .append(',').append(csv(g.getUsageCount()))
              .append(',').append(csv(g.getCreatedAt()))
              .append(',').append(csv(g.getUpdatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gpts.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> updateGpt(@PathVariable Long id,
                                          @RequestBody GptUpsertRequest request) {
        return ApiResponse.success(gptService.updateGpt(id, request));
    }

    @PutMapping("/{id}/public")
    public ApiResponse<Boolean> updatePublic(@PathVariable Long id,
                                             @RequestParam boolean isPublic) {
        return ApiResponse.success(gptService.updatePublic(id, isPublic));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteGpt(@PathVariable Long id) {
        return ApiResponse.success(gptService.deleteGpt(id));
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
