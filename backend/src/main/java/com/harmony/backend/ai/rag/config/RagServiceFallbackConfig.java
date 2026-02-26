package com.harmony.backend.ai.rag.config;

import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.rag.service.impl.NoopRagService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagServiceFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(name = "ragJdbcTemplate")
    public RagService ragServiceFallback() {
        return new NoopRagService();
    }
}
