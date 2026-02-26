package com.harmony.backend.ai.rag.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OcrSettings {
    private boolean enabled;
    private int maxImagesPerRequest;
    private long maxImageBytes;
    private int maxPdfPages;
    private int rateLimitPerDay;
    private int rateLimitWindowSeconds;
    private int defaultUserQuota;
}
