package com.harmony.backend.ai.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.rag.ocr")
public class RagOcrProperties {
    private boolean enabled = true;
    private String tessdataPath = "";
    private String language = "eng+chi_sim";
    private int maxImagesPerRequest = 50;
    private long maxImageBytes = 5 * 1024 * 1024;
    private int maxPdfPages = 8;
    private int defaultUserQuota = 200;
    private int rateLimitPerDay = 200;
    private int rateLimitWindowSeconds = 86400;
}
