package com.harmony.backend.modules.gptstore.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.gptstore")
public class GptStoreProperties {
    private boolean autoApprove = false;
    private List<String> blockedKeywords = new ArrayList<>();
}
