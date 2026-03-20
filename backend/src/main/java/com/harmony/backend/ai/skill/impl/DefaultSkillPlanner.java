package com.harmony.backend.ai.skill.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.AgentSkillRegistry;
import com.harmony.backend.ai.skill.SkillCandidate;
import com.harmony.backend.ai.skill.SkillInputField;
import com.harmony.backend.ai.skill.SkillPlan;
import com.harmony.backend.ai.skill.SkillPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DefaultSkillPlanner implements SkillPlanner {

    private final AgentSkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public SkillPlan plan(String userPrompt, String requestedSkill, String rawInput, List<String> allowedSkills) {
        if (allowedSkills == null || allowedSkills.isEmpty()) {
            return new SkillPlan(null, List.of(), normalizeRawInput(rawInput), "no allowed skills");
        }
        List<SkillCandidate> candidates = new ArrayList<>();
        for (String allowedSkill : allowedSkills) {
            AgentSkillDefinition definition = skillRegistry.get(allowedSkill);
            if (definition == null) {
                continue;
            }
            int score = scoreSkill(definition, userPrompt, requestedSkill, rawInput);
            String reason = buildReason(definition, requestedSkill, rawInput, score);
            candidates.add(new SkillCandidate(definition.getKey(), score, reason));
        }
        candidates.sort(Comparator.comparingInt(SkillCandidate::getScore).reversed()
                .thenComparing(SkillCandidate::getSkillKey));
        String selected = candidates.isEmpty() ? null : candidates.get(0).getSkillKey();
        AgentSkillDefinition selectedDefinition = selected == null ? null : skillRegistry.get(selected);
        String normalizedInput = normalizeInputForSkill(selectedDefinition, userPrompt, rawInput);
        return new SkillPlan(selected, candidates, normalizedInput,
                selected == null ? "no matching skill candidate" : "selected highest scoring allowed skill");
    }

    private int scoreSkill(AgentSkillDefinition definition, String userPrompt, String requestedSkill, String rawInput) {
        int score = 0;
        String prompt = normalize(userPrompt);
        String requested = normalize(requestedSkill);
        if (StringUtils.hasText(requested)) {
            if (requested.equals(normalize(definition.getKey()))) {
                score += 100;
            } else if (requested.equals(normalize(definition.getName()))) {
                score += 80;
            }
        }
        score += scoreText(prompt, definition.getKey()) * 5;
        score += scoreText(prompt, definition.getName()) * 4;
        score += scoreText(prompt, definition.getDescription()) * 2;
        if (definition.getToolKeys() != null) {
            for (String toolKey : definition.getToolKeys()) {
                score += scoreText(prompt, toolKey);
            }
        }
        score += scoreInputSchema(definition, rawInput);
        return score;
    }

    private int scoreInputSchema(AgentSkillDefinition definition, String rawInput) {
        if (!StringUtils.hasText(rawInput) || definition.getInputSchema() == null || definition.getInputSchema().isEmpty()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(rawInput);
            if (!root.isObject()) {
                return 0;
            }
            int score = 0;
            for (SkillInputField field : definition.getInputSchema()) {
                if (field == null || !StringUtils.hasText(field.getKey())) {
                    continue;
                }
                JsonNode node = root.get(field.getKey());
                if (node != null && !node.isNull()) {
                    score += Boolean.TRUE.equals(field.getRequired()) ? 15 : 8;
                }
            }
            return score;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int scoreText(String prompt, String candidate) {
        if (!StringUtils.hasText(prompt) || !StringUtils.hasText(candidate)) {
            return 0;
        }
        String normalized = normalize(candidate);
        if (prompt.contains(normalized)) {
            return 6;
        }
        int score = 0;
        for (String token : normalized.split("[\\s,_-]+")) {
            if (token.length() < 3) {
                continue;
            }
            if (prompt.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private String buildReason(AgentSkillDefinition definition, String requestedSkill, String rawInput, int score) {
        List<String> reasons = new ArrayList<>();
        if (StringUtils.hasText(requestedSkill)
                && normalize(requestedSkill).equals(normalize(definition.getKey()))) {
            reasons.add("explicit skill request matched");
        }
        if (definition.getInputSchema() != null && !definition.getInputSchema().isEmpty() && scoreInputSchema(definition, rawInput) > 0) {
            reasons.add("input schema matched");
        }
        if (reasons.isEmpty()) {
            reasons.add("keyword score=" + score);
        }
        return String.join(", ", reasons);
    }

    private String normalizeInputForSkill(AgentSkillDefinition definition, String userPrompt, String rawInput) {
        String normalizedRaw = normalizeRawInput(rawInput);
        if (definition == null || definition.getInputSchema() == null || definition.getInputSchema().isEmpty()) {
            return normalizedRaw;
        }
        if (looksLikeValidInputObject(definition, normalizedRaw)) {
            return normalizedRaw;
        }
        String source = StringUtils.hasText(normalizedRaw) ? normalizedRaw : normalizeRawInput(userPrompt);
        List<SkillInputField> requiredFields = definition.getInputSchema().stream()
                .filter(field -> field != null && StringUtils.hasText(field.getKey()) && Boolean.TRUE.equals(field.getRequired()))
                .toList();
        if (requiredFields.size() == 1) {
            return writeSingleFieldPayload(requiredFields.get(0).getKey(), source);
        }
        List<SkillInputField> allFields = definition.getInputSchema().stream()
                .filter(field -> field != null && StringUtils.hasText(field.getKey()))
                .toList();
        if (allFields.size() == 1) {
            return writeSingleFieldPayload(allFields.get(0).getKey(), source);
        }
        return normalizedRaw;
    }

    private boolean looksLikeValidInputObject(AgentSkillDefinition definition, String rawInput) {
        if (!StringUtils.hasText(rawInput)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(rawInput);
            if (!root.isObject()) {
                return false;
            }
            for (SkillInputField field : definition.getInputSchema()) {
                if (field == null || !StringUtils.hasText(field.getKey())) {
                    continue;
                }
                if (Boolean.TRUE.equals(field.getRequired())) {
                    JsonNode value = root.get(field.getKey());
                    if (value == null || value.isNull()) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String writeSingleFieldPayload(String fieldKey, String value) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(fieldKey, value == null ? "" : value);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private String normalizeRawInput(String rawInput) {
        return rawInput == null ? "" : rawInput.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}