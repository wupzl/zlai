package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.chat.controller.response.ModelPricingVO;
import com.harmony.backend.modules.chat.service.ModelPricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.harmony.backend.common.entity.ModelPricingLog;
import com.harmony.backend.common.response.PageResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/model-pricing")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ModelPricingController {
    private final ModelPricingService pricingService;

    @GetMapping
    public ApiResponse<List<ModelPricingVO>> listPricing() {
        return ApiResponse.success(pricingService.listPricing());
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportPricing() {
        List<ModelPricingVO> list = pricingService.listPricing();
        StringBuilder sb = new StringBuilder();
        sb.append("model,multiplier,updated_at\n");
        for (ModelPricingVO v : list) {
            sb.append(csv(v.getModel()))
              .append(',').append(csv(v.getMultiplier()))
              .append(',').append(csv(v.getUpdatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=model_pricing.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @PutMapping
    public ApiResponse<ModelPricingVO> updatePricing(@RequestParam String model,
                                                     @RequestParam Double multiplier) {
        Long adminId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(pricingService.updatePricing(model, multiplier, adminId));
    }

    @GetMapping("/logs")
    public ApiResponse<PageResult<ModelPricingLog>> listLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ApiResponse.success(pricingService.listLogs(page, size, startTime, endTime));
    }

    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        List<ModelPricingLog> logs = pricingService.listLogs(1, 10000, startTime, endTime).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("model,old_multiplier,new_multiplier,updated_by,updated_at\n");
        for (ModelPricingLog logItem : logs) {
            sb.append(csv(logItem.getModel()))
              .append(',').append(csv(logItem.getOldMultiplier()))
              .append(',').append(csv(logItem.getNewMultiplier()))
              .append(',').append(csv(logItem.getUpdatedBy()))
              .append(',').append(csv(logItem.getUpdatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=model_pricing_logs.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
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
