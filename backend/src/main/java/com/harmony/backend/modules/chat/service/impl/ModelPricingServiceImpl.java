package com.harmony.backend.modules.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.harmony.backend.common.entity.ModelPricing;
import com.harmony.backend.common.entity.ModelPricingLog;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.ModelPricingMapper;
import com.harmony.backend.common.mapper.ModelPricingLogMapper;
import com.harmony.backend.modules.chat.config.BillingProperties;
import com.harmony.backend.modules.chat.controller.response.ModelPricingVO;
import com.harmony.backend.modules.chat.service.ModelPricingService;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.PageResultUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.data.redis.core.RedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelPricingServiceImpl implements ModelPricingService {
    private final ModelPricingMapper pricingMapper;
    private final ModelPricingLogMapper logMapper;
    private final BillingProperties billingProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String PRICING_CACHE_KEY = "model:pricing:list";
    private static final java.time.Duration PRICING_CACHE_TTL = java.time.Duration.ofMinutes(10);

    @PostConstruct
    public void loadPricingOverrides() {
        try {
            List<ModelPricing> list = pricingMapper.selectList(new LambdaQueryWrapper<>());
            if (list == null || list.isEmpty()) {
                return;
            }
            Map<String, Double> target = billingProperties.getModelMultipliers();
            for (ModelPricing item : list) {
                if (item.getModel() != null && item.getMultiplier() != null) {
                    target.put(item.getModel(), item.getMultiplier());
                }
            }
        } catch (Exception e) {
            log.warn("Load model pricing failed: {}", e.getMessage());
        }
    }

    @Override
    public List<ModelPricingVO> listPricing() {
        Object cached = redisTemplate.opsForValue().get(PRICING_CACHE_KEY);
        if (cached instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof ModelPricingVO) {
            @SuppressWarnings("unchecked")
            List<ModelPricingVO> cachedList = (List<ModelPricingVO>) cached;
            return cachedList;
        }
        List<ModelPricingVO> result = buildPricingList();
        redisTemplate.opsForValue().set(PRICING_CACHE_KEY, result, PRICING_CACHE_TTL);
        return result;
    }

    private List<ModelPricingVO> buildPricingList() {
        Map<String, Double> multipliers = billingProperties.getModelMultipliers();
        List<String> models = billingProperties.getAvailableModels();
        List<ModelPricing> dbPricing = pricingMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, ModelPricing> dbMap = new java.util.HashMap<>();
        for (ModelPricing item : dbPricing) {
            if (item.getModel() != null) {
                dbMap.put(item.getModel(), item);
            }
        }
        List<ModelPricingVO> result = new ArrayList<>();
        if (models == null || models.isEmpty()) {
            models = new ArrayList<>(billingProperties.getModelLimits().keySet());
        }
        if (models != null) {
            for (String model : models) {
                ModelPricing db = dbMap.get(model);
                Double multiplier = db != null && db.getMultiplier() != null
                        ? db.getMultiplier()
                        : multipliers.getOrDefault(model, 1.0);
                java.time.LocalDateTime updatedAt = db != null ? db.getUpdatedAt() : null;
                result.add(new ModelPricingVO(model, multiplier, formatTime(updatedAt)));
            }
        }
        result.sort(Comparator.comparing(ModelPricingVO::getModel, String::compareToIgnoreCase));
        return result;
    }

    @Override
    public ModelPricingVO updatePricing(String model, Double multiplier, Long updatedBy) {
        if (!StringUtils.hasText(model)) {
            throw new BusinessException(400, "Model is required");
        }
        if (multiplier == null || multiplier <= 0) {
            throw new BusinessException(400, "Multiplier must be greater than 0");
        }
        String trimmed = model.trim();
        ModelPricing existing = pricingMapper.selectOne(new LambdaQueryWrapper<ModelPricing>()
                .eq(ModelPricing::getModel, trimmed));
        Double oldMultiplier = existing != null ? existing.getMultiplier() : billingProperties.getModelMultipliers().get(trimmed);
        if (existing == null) {
            ModelPricing created = new ModelPricing();
            created.setModel(trimmed);
            created.setMultiplier(multiplier);
            created.setUpdatedBy(updatedBy);
            created.setUpdatedAt(LocalDateTime.now());
            pricingMapper.insert(created);
        } else {
            existing.setMultiplier(multiplier);
            existing.setUpdatedBy(updatedBy);
            existing.setUpdatedAt(LocalDateTime.now());
            pricingMapper.updateById(existing);
        }
        billingProperties.getModelMultipliers().put(trimmed, multiplier);
        recordLog(trimmed, oldMultiplier, multiplier, updatedBy);
        List<ModelPricingVO> refreshed = buildPricingList();
        redisTemplate.opsForValue().set(PRICING_CACHE_KEY, refreshed, PRICING_CACHE_TTL);
        ModelPricingVO updated = refreshed.stream()
                .filter(item -> trimmed.equals(item.getModel()))
                .findFirst()
                .orElse(new ModelPricingVO(trimmed, multiplier, formatTime(LocalDateTime.now())));
        return updated;
    }

    private String formatTime(LocalDateTime time) {
        if (time == null) {
            return null;
        }
        return time.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    public PageResult<ModelPricingLog> listLogs(int page, int size, String startTime, String endTime) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        Page<ModelPricingLog> pageResult = new Page<>(safePage, safeSize);
        var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelPricingLog>();
        applyTimeRange(query, startTime, endTime, ModelPricingLog::getUpdatedAt);
        query.orderByDesc(ModelPricingLog::getUpdatedAt);
        Page<ModelPricingLog> result = logMapper.selectPage(pageResult, query);
        return PageResultUtils.from(result);
    }

    private <T> void applyTimeRange(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T> query,
                                    String startTime,
                                    String endTime,
                                    com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> field) {
        java.time.LocalDateTime start = parseDateTime(startTime);
        java.time.LocalDateTime end = parseDateTime(endTime);
        if (start != null) {
            query.ge(field, start);
        }
        if (end != null) {
            query.le(field, end);
        }
    }

    private java.time.LocalDateTime parseDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            if (value.length() <= 10) {
                java.time.LocalDate date = java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ISO_DATE);
                return date.atStartOfDay();
            }
            try {
                return java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception ignored) {
                return java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void recordLog(String model, Double oldMultiplier, Double newMultiplier, Long updatedBy) {
        try {
            ModelPricingLog logItem = new ModelPricingLog();
            logItem.setModel(model);
            logItem.setOldMultiplier(oldMultiplier);
            logItem.setNewMultiplier(newMultiplier);
            logItem.setUpdatedBy(updatedBy);
            logItem.setUpdatedAt(LocalDateTime.now());
            logMapper.insert(logItem);
        } catch (Exception e) {
            log.warn("Record pricing log failed: {}", e.getMessage());
        }
    }
}
