package com.harmony.backend.modules.admin.controller.request;

import lombok.Data;

@Data
public class OcrSettingsRequest {
    private Boolean enabled;
    private Integer maxImagesPerRequest;
    private Long maxImageBytes;
    private Integer maxPdfPages;
    private Integer rateLimitPerDay;
    private Integer rateLimitWindowSeconds;
    private Integer defaultUserQuota;
}
