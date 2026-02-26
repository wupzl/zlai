package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.admin.service.AdminChatService;
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
@RequestMapping("/api/admin/chats")
@PreAuthorize("hasRole('ADMIN')")
public class AdminChatController {

    private final AdminChatService chatService;

    @GetMapping("/sessions")
    public ApiResponse<PageResult<Session>> listSessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(chatService.listSessions(userId, keyword, page, size));
    }

    @GetMapping("/sessions/{chatId}")
    public ApiResponse<Session> getSessionDetail(@PathVariable String chatId) {
        return ApiResponse.success(chatService.getSessionDetail(chatId));
    }

    @GetMapping("/sessions/export")
    public ResponseEntity<byte[]> exportSessions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String keyword) {
        List<Session> sessions = chatService.listSessions(userId, keyword, 1, 10000).getContent();
        StringBuilder sb = new StringBuilder();
        sb.append("chat_id,user_id,title,model,tool_model,message_count,rag_enabled,last_active_time,created_at\n");
        for (Session s : sessions) {
            sb.append(csv(s.getChatId()))
              .append(',').append(csv(s.getUserId()))
              .append(',').append(csv(s.getTitle()))
              .append(',').append(csv(s.getModel()))
              .append(',').append(csv(s.getToolModel()))
              .append(',').append(csv(s.getMessageCount()))
              .append(',').append(csv(s.getRagEnabled()))
              .append(',').append(csv(s.getLastActiveTime()))
              .append(',').append(csv(s.getCreatedAt()))
              .append('\n');
        }
        byte[] body = withBom(sb.toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chat_sessions.csv")
                .contentType(new MediaType("text", "csv"))
                .body(body);
    }

    @GetMapping("/messages")
    public ApiResponse<PageResult<Message>> listMessages(
            @RequestParam String chatId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(chatService.listMessages(chatId, page, size));
    }

    @GetMapping("/messages/{messageId}")
    public ApiResponse<Message> getMessageDetail(@PathVariable String messageId) {
        return ApiResponse.success(chatService.getMessageDetail(messageId));
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
