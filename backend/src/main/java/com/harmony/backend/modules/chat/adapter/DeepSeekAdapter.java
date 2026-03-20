package com.harmony.backend.modules.chat.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@Slf4j
public class DeepSeekAdapter extends AbstractOpenAiCompatibleAdapter {

    @Value("${app.ai.deepseek.key}")
    private String apiKey;

    @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    public DeepSeekAdapter(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super(webClientBuilder, objectMapper);
    }

    @Override
    public boolean supports(String model) {
        return model != null && model.startsWith("deepseek");
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getBaseUrl() {
        return baseUrl;
    }

    @Override
    protected String getProviderName() {
        return "DeepSeek";
    }
}
