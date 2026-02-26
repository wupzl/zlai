package com.harmony.backend.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.LoginLog;
import com.harmony.backend.common.entity.SystemLog;
import com.harmony.backend.common.entity.TokenConsumption;
import com.harmony.backend.common.mapper.LoginLogMapper;
import com.harmony.backend.common.mapper.SystemLogMapper;
import com.harmony.backend.common.mapper.TokenConsumptionMapper;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.harmony.backend.modules.admin.service.AdminLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

@Service
@RequiredArgsConstructor
public class AdminLogServiceImpl extends ServiceImpl<SystemLogMapper, SystemLog> implements AdminLogService {

    private final LoginLogMapper loginLogMapper;
    private final TokenConsumptionMapper tokenConsumptionMapper;

    @Override
    public PageResult<LoginLog> listLoginLogs(Long userId, String startTime, String endTime, int page, int size) {
        Page<LoginLog> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<LoginLog> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(LoginLog::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, LoginLog::getLoginTime);
        query.orderByDesc(LoginLog::getLoginTime);
        Page<LoginLog> result = loginLogMapper.selectPage(pageResult, query);
        long total = loginLogMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public PageResult<TokenConsumption> listTokenLogs(Long userId, String startTime, String endTime, int page, int size) {
        Page<TokenConsumption> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<TokenConsumption> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(TokenConsumption::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, TokenConsumption::getCreatedAt);
        query.orderByDesc(TokenConsumption::getCreatedAt);
        Page<TokenConsumption> result = tokenConsumptionMapper.selectPage(pageResult, query);
        long total = tokenConsumptionMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public PageResult<SystemLog> listSystemLogs(Long userId, String startTime, String endTime, int page, int size) {
        Page<SystemLog> pageResult = new Page<>(page, size);
        LambdaQueryWrapper<SystemLog> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(SystemLog::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, SystemLog::getCreatedAt);
        query.orderByDesc(SystemLog::getCreatedAt);
        Page<SystemLog> result = baseMapper.selectPage(pageResult, query);
        long total = baseMapper.selectCount(query);
        result.setTotal(total);
        return PageResultUtils.from(result);
    }

    @Override
    public long countSystemLogs(Long userId, String startTime, String endTime) {
        LambdaQueryWrapper<SystemLog> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(SystemLog::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, SystemLog::getCreatedAt);
        return baseMapper.selectCount(query);
    }

    @Override
    public long countLoginLogs(Long userId, String startTime, String endTime) {
        LambdaQueryWrapper<LoginLog> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(LoginLog::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, LoginLog::getLoginTime);
        return loginLogMapper.selectCount(query);
    }

    @Override
    public long countTokenLogs(Long userId, String startTime, String endTime) {
        LambdaQueryWrapper<TokenConsumption> query = new LambdaQueryWrapper<>();
        if (userId != null) {
            query.eq(TokenConsumption::getUserId, userId);
        }
        applyTimeRange(query, startTime, endTime, TokenConsumption::getCreatedAt);
        return tokenConsumptionMapper.selectCount(query);
    }

    private <T> void applyTimeRange(LambdaQueryWrapper<T> query, String startTime, String endTime, SFunction<T, ?> field) {
        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        if (start != null) {
            query.ge(field, start);
        }
        if (end != null) {
            query.le(field, end);
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            if (value.length() <= 10) {
                LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_DATE);
                return date.atStartOfDay();
            }
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
