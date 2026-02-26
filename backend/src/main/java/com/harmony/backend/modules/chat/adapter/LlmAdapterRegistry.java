package com.harmony.backend.modules.chat.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LlmAdapterRegistry {

    private final List<LlmAdapter> adapters;
    private final MockAdapter mockAdapter;
    @Value("${app.llm.mock-enabled:false}")
    private boolean mockEnabled;

    public LlmAdapter getAdapter(String model) {
        if (mockEnabled) {
            return mockAdapter;
        }
        return adapters.stream()
                .filter(adapter -> adapter.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + model));
    }
}
