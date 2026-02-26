package com.harmony.backend.common.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;

import java.util.Locale;

public class TokenCounter {
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding DEFAULT_ENCODING = REGISTRY.getEncodingForModel(ModelType.GPT_3_5_TURBO);

    private TokenCounter() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return DEFAULT_ENCODING.countTokens(text);
    }

    public static int estimateMessageTokens(String role, String content) {
        if (content == null || content.isBlank()) {
            return role == null ? 2 : 3;
        }
        // Base tokens for OpenAI message format overhead
        int base = DEFAULT_ENCODING.countTokens(content);
        int roleOverhead = role == null ? 2 : 3;
        return base + roleOverhead;
    }

    public static int countTokensForModel(String model, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            ModelType modelType = resolveModelType(model);
            Encoding encoding = REGISTRY.getEncodingForModel(modelType);
            return encoding.countTokens(text);
        } catch (Exception e) {
            return DEFAULT_ENCODING.countTokens(text);
        }
    }

    private static ModelType resolveModelType(String model) {
        if (model == null || model.isBlank()) {
            return ModelType.GPT_3_5_TURBO;
        }
        try {
            return ModelType.fromName(model).orElse(ModelType.GPT_3_5_TURBO);
        } catch (Exception ignored) {
            // fall through
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        if (normalized.contains("gpt-4o")) {
            return ModelType.GPT_4O;
        }
        if (normalized.contains("gpt-4")) {
            return ModelType.GPT_4;
        }
        if (normalized.contains("claude")) {
            // closest OpenAI tokenization for rough billing
            return ModelType.GPT_4;
        }
        if (normalized.contains("deepseek")) {
            return ModelType.GPT_3_5_TURBO;
        }
        return ModelType.GPT_3_5_TURBO;
    }
}
