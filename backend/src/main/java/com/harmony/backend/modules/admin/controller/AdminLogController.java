package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.entity.SystemLog;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.admin.service.AdminLogService;
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
@RequestMapping("/api/admin/logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLogController {

    private final AdminLogService logService;

    @GetMapping("/login")
    public ApiResponse<PageResult<LoginLog>> listLoginLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(logService.listLoginLogs(userId, startTime, endTime, page, size));
    }

    @GetMapping("/tokens")
    public ApiResponse<PageResult<TokenConsumption>> listTokenLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(logService.listTokenLogs(userId, startTime, endTime, page, size));
    }

    @GetMapping("/system")
    public ApiResponse<PageResult<SystemLog>> listSystemLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(logService.listSystemLogs(userId, startTime, endTime, page, size));
    }

    @GetMapping("/system/count")
    public ApiResponse<Long> countSystemLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ApiResponse.success(logService.countSystemLogs(userId, startTime, endTime));
    }

    @GetMapping("/login/count")
    public ApiResponse<Long> countLoginLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ApiResponse.success(logService.countLoginLogs(userId, startTime, endTime));
    }

    @GetMapping("/tokens/count")
    public ApiResponse<Long> countTokenLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ApiResponse.success(logService.countTokenLogs(userId, startTime, endTime));
    }

    @GetMapping("/count")
    public ApiResponse<Long> countAllLogs(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        long total = logService.countSystemLogs(userId, startTime, endTime)
                + logService.countLoginLogs(userId, startTime, endTime)
                + logService.countTokenLogs(userId, startTime, endTime);
        return ApiResponse.success(total);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportLogs(
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("type,time,user_id,status,content\n");
        if ("login".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
            List<LoginLog> logs = logService.listLoginLogs(userId, startTime, endTime, 1, 10000).getContent();
            for (LoginLog l : logs) {
                sb.append(csv("login"))
                  .append(',').append(csv(l.getLoginTime()))
                  .append(',').append(csv(l.getUserId()))
                  .append(',').append(csv(Boolean.TRUE.equals(l.getSuccess()) ? "SUCCESS" : "FAILED"))
                  .append(',').append(csv(l.getFailReason()))
                  .append('\n');
            }
        }
        if ("token".equalsIgnoreCase(type) || "tokens".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
            List<TokenConsumption> logs = logService.listTokenLogs(userId, startTime, endTime, 1, 10000).getContent();
            for (TokenConsumption t : logs) {
                sb.append(csv("token"))
                  .append(',').append(csv(t.getCreatedAt()))
                  .append(',').append(csv(t.getUserId()))
                  .append(',').append(csv("SUCCESS"))
                  .append(',').append(csv("model=" + t.getModel() + ", total=" + t.getTotalTokens()))
                  .append('\n');
            }
        }
        if ("system".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
            List<SystemLog> logs = logService.listSystemLogs(userId, startTime, endTime, 1, 10000).getContent();
            for (SystemLog s : logs) {
                sb.append(csv("system"))
                  .append(',').append(csv(s.getCreatedAt()))
                  .append(',').append(csv(s.getUserId()))
                  .append(',').append(csv(s.getStatus()))
                  .append(',').append(csv(s.getOperation() + ":" + s.getModule()))
                  .append('\n');
            }
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=logs.csv")
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
