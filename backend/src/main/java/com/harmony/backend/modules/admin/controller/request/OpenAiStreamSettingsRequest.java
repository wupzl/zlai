package com.harmony.backend.modules.admin.controller.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiStreamSettingsRequest {
    private Boolean enabled;
}
