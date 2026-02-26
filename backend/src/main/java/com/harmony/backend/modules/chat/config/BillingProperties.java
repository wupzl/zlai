package com.harmony.backend.modules.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "app.chat.billing")
public class BillingProperties {
    private Integer maxCompletionTokens = 2048;
    private Map<String, Double> modelMultipliers = new HashMap<>();
    private Map<String, ModelLimit> modelLimits = new HashMap<>();
    private List<String> availableModels;

    @PostConstruct
    public void normalizeModelKeys() {
        modelMultipliers = normalizeKeys(modelMultipliers);
        modelLimits = normalizeKeys(modelLimits);
    }

    public double getMultiplier(String model) {
        if (model == null || model.isBlank()) {
            return 1.0;
        }
        return modelMultipliers.getOrDefault(model, 1.0);
    }

    public ModelLimit getModelLimit(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return modelLimits.get(model);
    }

    private <T> Map<String, T> normalizeKeys(Map<String, T> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Map<String, T> normalized = new HashMap<>(input);
        for (Map.Entry<String, T> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String fixed = key.replace('_', '.');
            if (!fixed.equals(key) && !normalized.containsKey(fixed)) {
                normalized.put(fixed, entry.getValue());
            }
        }
        return normalized;
    }

    @Data
    public static class ModelLimit {
        private Integer maxMessages;
        private Integer maxChars;
        private Integer maxCompletionTokens;
    }
}
