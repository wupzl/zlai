package com.harmony.backend.ai.tool.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolHandler;
import com.harmony.backend.common.util.TokenCounter;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class LlmTextToolHandler implements ToolHandler {

    private final ObjectMapper objectMapper;
    private final LlmAdapterRegistry adapterRegistry;

    @Value("${app.tools.model:deepseek-chat}")
    private String toolModel;

    @Override
    public boolean supports(String toolKey) {
        if (!StringUtils.hasText(toolKey)) {
            return false;
        }
        String key = toolKey.trim().toLowerCase();
        return "translate".equals(key) || "summarize".equals(key);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getToolKey())) {
            return ToolExecutionResult.fail("Tool key is required");
        }
        String key = request.getToolKey().trim().toLowerCase();
        if ("translate".equals(key)) {
            return executeTranslation(request.getInput(), request.getModel());
        }
        if ("summarize".equals(key)) {
            return executeSummarize(request.getInput(), request.getModel());
        }
        return ToolExecutionResult.fail("Tool execution not implemented");
    }

    private ToolExecutionResult executeTranslation(String input, String modelOverride) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Translation input is required");
        }
        TranslationInput parsed = parseTranslationInput(input);
        String target = parsed.target != null ? parsed.target : "Chinese";
        String text = parsed.text != null ? parsed.text : input;
        List<String> targets = splitTargets(target);
        if (targets.size() <= 1) {
            String system = "You are a professional translator. Translate the user text to " + target
                    + ". Output only the translated text.";
            return callLlm(system, text, modelOverride);
        }
        StringBuilder combined = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        String usedModel = resolveToolModel(modelOverride);
        for (String t : targets) {
            String system = "You are a professional translator. Translate the user text to " + t
                    + ". Output only the translated text.";
            ToolExecutionResult result = callLlm(system, text, modelOverride);
            if (result == null || !result.isSuccess()) {
                continue;
            }
            if (combined.length() > 0) {
                combined.append("\n\n");
            }
            combined.append(t).append(":\n").append(result.getOutput());
            if (result.getPromptTokens() != null) {
                promptTokens += result.getPromptTokens();
            }
            if (result.getCompletionTokens() != null) {
                completionTokens += result.getCompletionTokens();
            }
        }
        if (combined.length() == 0) {
            return ToolExecutionResult.fail("Translation failed");
        }
        return ToolExecutionResult.ok(combined.toString(), usedModel, promptTokens, completionTokens);
    }

    private ToolExecutionResult executeSummarize(String input, String modelOverride) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Summarize input is required");
        }
        String system = "You are a professional summarizer. Provide a concise summary in 3-6 bullet points.";
        return callLlm(system, input, modelOverride);
    }

    private ToolExecutionResult callLlm(String systemPrompt, String userText, String modelOverride) {
        try {
            String model = resolveToolModel(modelOverride);
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            List<LlmMessage> messages = new ArrayList<>();
            if (StringUtils.hasText(systemPrompt)) {
                messages.add(new LlmMessage("system", systemPrompt));
            }
            messages.add(new LlmMessage("user", userText));
            String response = adapter.chat(messages, model);
            if (!StringUtils.hasText(response)) {
                return ToolExecutionResult.fail("LLM returned empty response");
            }
            int promptTokens = 0;
            if (StringUtils.hasText(systemPrompt)) {
                promptTokens += TokenCounter.estimateMessageTokens("system", systemPrompt);
            }
            if (StringUtils.hasText(userText)) {
                promptTokens += TokenCounter.estimateMessageTokens("user", userText);
            }
            int completionTokens = TokenCounter.estimateMessageTokens("assistant", response);
            return ToolExecutionResult.ok(response.trim(), model, promptTokens, completionTokens);
        } catch (Exception e) {
            log.warn("Tool LLM request failed: {}", e.getMessage());
            return ToolExecutionResult.fail("LLM request failed");
        }
    }

    private String resolveToolModel(String modelOverride) {
        if (StringUtils.hasText(modelOverride)) {
            return modelOverride.trim();
        }
        if (StringUtils.hasText(toolModel)) {
            return toolModel.trim();
        }
        return "deepseek-chat";
    }

    private TranslationInput parseTranslationInput(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> map = objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
                String text = map.get("text") != null ? map.get("text").toString() : null;
                Object targetObj = map.get("target");
                if (targetObj == null) {
                    targetObj = map.get("to");
                }
                String target = targetObj != null ? targetObj.toString() : null;
                return new TranslationInput(text, target);
            } catch (Exception ignored) {
            }
        }
        if (trimmed.toLowerCase().startsWith("to:")) {
            int idx = trimmed.indexOf('\n');
            if (idx > 0) {
                String target = trimmed.substring(3, idx).trim();
                String text = trimmed.substring(idx + 1).trim();
                return new TranslationInput(text, target);
            }
        }
        return new TranslationInput(trimmed, null);
    }

    private List<String> splitTargets(String target) {
        List<String> targets = new ArrayList<>();
        if (!StringUtils.hasText(target)) {
            return targets;
        }
        String normalized = target
                .replace("及", ",")
                .replace("和", ",")
                .replace("与", ",")
                .replace(" and ", ",")
                .replace(" AND ", ",")
                .replace("and", ",")
                .replace("AND", ",")
                .replaceAll("[、，。；：]", ",");
        String[] parts = normalized.split(",");
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty()) {
                targets.add(t);
            }
        }
        if (targets.isEmpty()) {
            targets.add(target.trim());
        }
        return targets;
    }

    private static class TranslationInput {
        private final String text;
        private final String target;

        private TranslationInput(String text, String target) {
            this.text = text;
            this.target = target;
        }
    }
}

