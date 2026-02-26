package com.harmony.backend.ai.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagOcrProperties.class)
public class RagOcrConfig {
}
