package com.harmony.backend.modules.admin.controller;

import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.response.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/admin/rag")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRagController {

    private final RagService ragService;

    @GetMapping("/documents")
    public ApiResponse<PageResult<RagDocumentSummary>> listDocuments(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(ragService.listDocumentsForAdmin(userId, page, size));
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResponse<Boolean> deleteDocument(@PathVariable String docId) {
        return ApiResponse.success(ragService.deleteDocumentForAdmin(docId));
    }
}
