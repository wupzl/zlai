package com.harmony.backend.modules.gptstore.service;

import com.harmony.backend.common.entity.Gpt;
import com.harmony.backend.modules.gptstore.config.GptStoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class GptModerationService {

    private final GptStoreProperties properties;

    public boolean shouldAutoApprove(Gpt draft) {
        if (!properties.isAutoApprove()) {
            return false;
        }
        return isSafe(draft);
    }

    public boolean isSafe(Gpt draft) {
        if (draft == null) {
            return false;
        }
        if (properties.getBlockedKeywords() == null || properties.getBlockedKeywords().isEmpty()) {
            return true;
        }
        String text = String.join(" ",
                safe(draft.getName()),
                safe(draft.getDescription()),
                safe(draft.getInstructions()))
                .toLowerCase(Locale.ROOT);
        for (String keyword : properties.getBlockedKeywords()) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
