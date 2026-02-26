package com.harmony.backend.modules.chat.service;

import com.harmony.backend.modules.chat.controller.response.ModelPricingVO;
import com.harmony.backend.common.entity.ModelPricingLog;
import com.harmony.backend.common.response.PageResult;

import java.util.List;

public interface ModelPricingService {
    List<ModelPricingVO> listPricing();

    ModelPricingVO updatePricing(String model, Double multiplier, Long updatedBy);

    PageResult<ModelPricingLog> listLogs(int page, int size, String startTime, String endTime);
}
