package com.harmony.backend.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagIngestFilepathProperties {

    private boolean ingestFilepathEnabled = false;

    private boolean ingestFilepathAdminOnly = true;

    private List<String> ingestFilepathAllowedRoots = new ArrayList<>();
}
