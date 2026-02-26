package com.harmony.backend.modules.gptstore.controller;

import com.harmony.backend.common.domain.ApiResponse;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.RequestUtils;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;
import com.harmony.backend.modules.gptstore.service.GptStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gpts")
@RequiredArgsConstructor
@Slf4j
public class GptStoreController {

    private final GptStoreService gptStoreService;

    @GetMapping("/public")
    public ApiResponse<PageResult<Gpt>> listPublic(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return ApiResponse.success(gptStoreService.listPublic(page, size, keyword, category));
    }

    @GetMapping("/public/{gptId}")
    public ApiResponse<Gpt> getPublicDetail(@PathVariable String gptId) {
        try {
            return ApiResponse.success(gptStoreService.getPublicDetail(gptId));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/mine")
    public ApiResponse<PageResult<Gpt>> listMine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = RequestUtils.getCurrentUserId();
        return ApiResponse.success(gptStoreService.listMine(userId, page, size));
    }

    @GetMapping("/{gptId}")
    public ApiResponse<Gpt> getDetail(@PathVariable String gptId) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(gptStoreService.getDetail(gptId, userId, isAdmin));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Gpt> create(@RequestBody GptUpsertRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(gptStoreService.create(userId, isAdmin, request));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Create GPT failed", e);
            return ApiResponse.error("Create GPT failed");
        }
    }

    @PutMapping("/{gptId}")
    public ApiResponse<Gpt> update(@PathVariable String gptId,
                                   @RequestBody GptUpsertRequest request) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(gptStoreService.update(gptId, userId, isAdmin, request));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Update GPT failed: gptId={}", gptId, e);
            return ApiResponse.error("Update GPT failed");
        }
    }

    @DeleteMapping("/{gptId}")
    public ApiResponse<Boolean> delete(@PathVariable String gptId) {
        Long userId = RequestUtils.getCurrentUserId();
        boolean isAdmin = RequestUtils.isAdmin();
        try {
            return ApiResponse.success(gptStoreService.delete(gptId, userId, isAdmin));
        } catch (BusinessException e) {
            return ApiResponse.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Delete GPT failed: gptId={}", gptId, e);
            return ApiResponse.error("Delete GPT failed");
        }
    }
}
