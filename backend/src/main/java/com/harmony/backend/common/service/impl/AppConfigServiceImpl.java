package com.harmony.backend.common.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.harmony.backend.common.entity.AppConfig;
import com.harmony.backend.common.mapper.AppConfigMapper;
import com.harmony.backend.common.service.AppConfigService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AppConfigServiceImpl extends ServiceImpl<AppConfigMapper, AppConfig> implements AppConfigService {

    @Override
    public String getValue(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        AppConfig config = lambdaQuery().eq(AppConfig::getConfigKey, key).one();
        return config != null ? config.getConfigValue() : null;
    }

    @Override
    public Map<String, String> getValues(List<String> keys) {
        Map<String, String> result = new HashMap<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }
        List<AppConfig> configs = lambdaQuery().in(AppConfig::getConfigKey, keys).list();
        for (AppConfig config : configs) {
            result.put(config.getConfigKey(), config.getConfigValue());
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
            return;
        }
        existing.setConfigValue(value);
        existing.setUpdatedBy(updatedBy);
        existing.setUpdatedAt(LocalDateTime.now());
        updateById(existing);
    }
}
