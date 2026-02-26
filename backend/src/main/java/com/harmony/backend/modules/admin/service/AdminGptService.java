package com.harmony.backend.modules.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.common.response.PageResult;

public interface AdminGptService extends IService<Gpt> {
    PageResult<Gpt> listGpts(int page, int size, String keyword);

    boolean updatePublic(Long id, boolean isPublic);

    boolean deleteGpt(Long id);

    Gpt getDetail(Long id);

    boolean updateGpt(Long id, com.harmony.backend.modules.gptstore.controller.request.GptUpsertRequest request);
}
