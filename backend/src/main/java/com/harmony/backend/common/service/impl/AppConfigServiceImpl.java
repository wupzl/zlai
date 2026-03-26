package com.harmony.backend.common.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.AppConfig;
import com.harmony.backend.common.mapper.AppConfigMapper;
import com.harmony.backend.common.service.AppConfigService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppConfigServiceImpl extends ServiceImpl<AppConfigMapper, AppConfig> implements AppConfigService {

    @Value("${app.config.cache-ttl-ms:3000}")
    private long cacheTtlMs;

    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    @Override
    public String getValue(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        CachedValue cachedValue = cache.get(key);
        if (cachedValue != null && !cachedValue.isExpired()) {
            return cachedValue.value();
        }
        AppConfig config = lambdaQuery().eq(AppConfig::getConfigKey, key).one();
        String value = config != null ? config.getConfigValue() : null;
        cache.put(key, new CachedValue(value, System.currentTimeMillis() + cacheTtlMs));
        return value;
    }

    @Override
    public Map<String, String> getValues(List<String> keys) {
        Map<String, String> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }
        long now = System.currentTimeMillis();
        List<String> missingKeys = new ArrayList<>();
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            CachedValue cachedValue = cache.get(key);
            if (cachedValue != null && !cachedValue.isExpired()) {
                result.put(key, cachedValue.value());
                continue;
            }
            missingKeys.add(key);
        }
        if (missingKeys.isEmpty()) {
            return result;
        }
        List<AppConfig> configs = lambdaQuery().in(AppConfig::getConfigKey, missingKeys).list();
        for (AppConfig config : configs) {
            result.put(config.getConfigKey(), config.getConfigValue());
            cache.put(config.getConfigKey(), new CachedValue(config.getConfigValue(), now + cacheTtlMs));
        }
        for (String missingKey : missingKeys) {
            if (!result.containsKey(missingKey)) {
                cache.put(missingKey, new CachedValue(null, now + cacheTtlMs));
                result.put(missingKey, null);
            }
        }
        return result;
    }

    @Override
    public void setValue(String key, String value, Long updatedBy) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        AppConfig existing = lambdaQuery().eq(AppConfig::getConfigKey, key).one();
        if (existing == null) {
            AppConfig config = new AppConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setUpdatedBy(updatedBy);
            config.setUpdatedAt(LocalDateTime.now());
            save(config);
            invalidate(key);
            return;
        }
        existing.setConfigValue(value);
        existing.setUpdatedBy(updatedBy);
        existing.setUpdatedAt(LocalDateTime.now());
        updateById(existing);
        invalidate(key);
    }

    @Override
    public void invalidate(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        cache.remove(key);
    }

    private record CachedValue(String value, long expiresAt) {
        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
