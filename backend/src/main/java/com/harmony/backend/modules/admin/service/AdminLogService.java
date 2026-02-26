package com.harmony.backend.modules.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.entity.SystemLog;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.response.PageResult;

public interface AdminLogService extends IService<SystemLog> {
    PageResult<LoginLog> listLoginLogs(Long userId, String startTime, String endTime, int page, int size);

    PageResult<TokenConsumption> listTokenLogs(Long userId, String startTime, String endTime, int page, int size);

    PageResult<SystemLog> listSystemLogs(Long userId, String startTime, String endTime, int page, int size);

    long countSystemLogs(Long userId, String startTime, String endTime);

    long countLoginLogs(Long userId, String startTime, String endTime);

    long countTokenLogs(Long userId, String startTime, String endTime);
}
