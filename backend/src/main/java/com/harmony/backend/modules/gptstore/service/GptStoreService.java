package com.harmony.backend.modules.gptstore.service;

import com.harmony.backend.common.entity.Gpt;
import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest;

public interface GptStoreService extends IService<Gpt> {

    PageResult<Gpt> listPublic(int page, int size, String keyword, String category);

    PageResult<Gpt> listMine(Long userId, int page, int size);

    Gpt getPublicDetail(String gptId);

    Gpt getDetail(String gptId, Long userId, boolean isAdmin);

    Gpt create(Long userId, boolean isAdmin, GptUpsertRequest request);

    Gpt update(String gptId, Long userId, boolean isAdmin, GptUpsertRequest request);

    boolean delete(String gptId, Long userId, boolean isAdmin);
}
