package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.admin.service.AdminUserService;
import com.harmony.backend.modules.admin.controller.request.AdminUserUpdateRequest;
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
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final AdminUserService userService;

    @GetMapping
    public ApiResponse<PageResult<User>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(userService.listUsers(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<User> getUser(@PathVariable Long id) {
        return ApiResponse.success(userService.getDetail(id));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportUsers(
            @RequestParam(required = false) String keyword) {
        List<User> users = userService.listUsers(1, 10000, keyword).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("id,username,nickname,role,status,token_balance,created_at,updated_at\n");
        for (User u : users) {
            sb.append(csv(u.getId()))
              .append(',').append(csv(u.getUsername()))
              .append(',').append(csv(u.getNickname()))
              .append(',').append(csv(u.getRole()))
              .append(',').append(csv(u.getStatus()))
              .append(',').append(csv(u.getTokenBalance()))
              .append(',').append(csv(u.getCreatedAt()))
              .append(',').append(csv(u.getUpdatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> updateUser(@PathVariable Long id,
                                           @RequestBody AdminUserUpdateRequest request) {
        return ApiResponse.success(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id,
                                             @RequestParam String status) {
        return ApiResponse.success(userService.updateStatus(id, status));
    }

    @PutMapping("/{id}/balance")
    public ApiResponse<Boolean> updateBalance(@PathVariable Long id,
                                              @RequestParam int delta) {
        return ApiResponse.success(userService.updateBalance(id, delta));
    }

    @PutMapping("/{id}/reset-password")
    public ApiResponse<Boolean> resetPassword(@PathVariable Long id,
                                              @RequestParam String newPassword) {
        return ApiResponse.success(userService.resetPassword(id, newPassword));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> deleteUser(@PathVariable Long id) {
        return ApiResponse.success(userService.deleteUser(id));
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
