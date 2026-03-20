package com.harmony.backend.ai.skill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.SkillInputField;
import com.harmony.backend.ai.skill.SkillStepDefinition;
import com.harmony.backend.ai.skill.model.SkillUpsertRequest;
import com.harmony.backend.ai.skill.model.SkillVO;
import com.harmony.backend.ai.skill.service.AgentSkillCatalogService;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.common.entity.AgentSkill;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.mapper.AgentSkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentSkillCatalogServiceImpl implements AgentSkillCatalogService {
    private static final String EXECUTION_MODE_SINGLE_TOOL = "single_tool";
    private static final String EXECUTION_MODE_PIPELINE = "pipeline";

    private final AgentSkillMapper agentSkillMapper;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public List<AgentSkillDefinition> listEnabledDefinitions() {
        return new ArrayList<>(loadEnabledDefinitionMap().values());
    }

    @Override
    public List<SkillVO> listManagedSkills() {
        return listDbSkills().stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    public AgentSkillDefinition getEnabledDefinition(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return loadEnabledDefinitionMap().get(normalizeKey(key));
    }

    @Override
    public List<String> expandToolsForSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        Map<String, AgentSkillDefinition> definitions = loadEnabledDefinitionMap();
        List<String> toolKeys = new ArrayList<>();
        for (String skill : skills) {
            AgentSkillDefinition definition = definitions.get(normalizeKey(skill));
            if (definition == null || definition.getToolKeys() == null) {
                continue;
            }
            for (String toolKey : definition.getToolKeys()) {
                if (StringUtils.hasText(toolKey) && !toolKeys.contains(toolKey)) {
                    toolKeys.add(toolKey.trim());
                }
            }
        }
        return toolKeys;
    }

    @Override
    public List<String> recommendSkillsForTools(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        Map<String, AgentSkillDefinition> definitions = loadEnabledDefinitionMap();
        List<String> skills = new ArrayList<>();
        for (AgentSkillDefinition definition : definitions.values()) {
            if (definition.getToolKeys() == null || definition.getToolKeys().isEmpty()) {
                continue;
            }
            boolean matched = tools.stream().anyMatch(tool -> definition.getToolKeys().contains(tool));
            if (matched && !skills.contains(definition.getKey())) {
                skills.add(definition.getKey());
            }
        }
        return skills;
    }

    @Override
    public String findSkillForTool(String toolKey, List<String> allowedSkills) {
        if (!StringUtils.hasText(toolKey)) {
            return null;
        }
        for (AgentSkillDefinition definition : loadEnabledDefinitionMap().values()) {
            if (definition.getToolKeys() == null || !definition.getToolKeys().contains(toolKey.trim())) {
                continue;
            }
            if (allowedSkills == null || allowedSkills.isEmpty() || allowedSkills.contains(definition.getKey())) {
                return definition.getKey();
            }
        }
        return null;
    }

    @Override
    public SkillVO create(SkillUpsertRequest request, Long adminId) {
        String key = normalizeAndValidateKey(request != null ? request.getKey() : null, true);
        ensureUniqueKey(key, null);
        AgentSkill skill = new AgentSkill();
        fillSkill(skill, key, request, true);
        agentSkillMapper.insert(skill);
        return toVo(findByKey(key));
    }

    @Override
    public SkillVO update(String skillKey, SkillUpsertRequest request, Long adminId) {
        AgentSkill existing = findByKey(skillKey);
        if (existing == null) {
            throw new BusinessException(404, "Skill not found");
        }
        String nextKey = normalizeAndValidateKey(request != null && StringUtils.hasText(request.getKey())
                ? request.getKey()
                : existing.getSkillKey(), false);
        ensureUniqueKey(nextKey, existing.getId());
        fillSkill(existing, nextKey, request, false);
        agentSkillMapper.updateById(existing);
        return toVo(findByKey(nextKey));
    }

    @Override
    public boolean delete(String skillKey) {
        AgentSkill existing = findByKey(skillKey);
        if (existing == null) {
            throw new BusinessException(404, "Skill not found");
        }
        return agentSkillMapper.deleteById(existing.getId()) > 0;
    }

    private void fillSkill(AgentSkill skill,
                           String key,
                           SkillUpsertRequest request,
                           boolean requireName) {
        if (request == null) {
            throw new BusinessException(400, "Skill payload is required");
        }
        String name = trimOrNull(request.getName());
        if (requireName && !StringUtils.hasText(name)) {
            throw new BusinessException(400, "Skill name is required");
        }
        if (!requireName && request.getName() != null && !StringUtils.hasText(name)) {
            throw new BusinessException(400, "Skill name cannot be empty");
        }
        List<String> toolKeys = normalizeToolKeys(request.getToolKeys());
        if (toolKeys.isEmpty()) {
            throw new BusinessException(400, "At least one tool is required");
        }
        skill.setSkillKey(key);
        if (request.getName() != null || requireName) {
            skill.setName(name);
        }
        if (request.getDescription() != null || requireName) {
            skill.setDescription(trimOrNull(request.getDescription()));
        }
        skill.setToolKeys(writeToolKeys(toolKeys));
        skill.setExecutionMode(normalizeExecutionMode(request.getExecutionMode()));
        skill.setInputSchema(writeInputSchema(normalizeInputSchema(request.getInputSchema())));
        skill.setStepConfig(writeStepConfig(normalizeStepConfig(request.getStepConfig(), toolKeys)));
        if (request.getEnabled() != null || requireName) {
            skill.setEnabled(request.getEnabled() == null || request.getEnabled());
        }
    }

    private AgentSkill findByKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return agentSkillMapper.selectOne(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getSkillKey, normalizeKey(key))
                .eq(AgentSkill::getIsDeleted, false)
                .last("limit 1"));
    }

    private void ensureUniqueKey(String key, Long excludeId) {
        AgentSkill existing = findByKey(key);
        if (existing != null && (excludeId == null || !excludeId.equals(existing.getId()))) {
            throw new BusinessException(400, "Skill key already exists");
        }
    }

    private String normalizeAndValidateKey(String key, boolean requireInput) {
        String normalized = normalizeKey(key);
        if (!StringUtils.hasText(normalized)) {
            if (requireInput) {
                throw new BusinessException(400, "Skill key is required");
            }
            return normalized;
        }
        if (!normalized.matches("[a-z0-9_\\-]{2,64}")) {
            throw new BusinessException(400, "Skill key must match [a-z0-9_-]{2,64}");
        }
        return normalized;
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizeToolKeys(List<String> toolKeys) {
        if (toolKeys == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String toolKey : toolKeys) {
            if (!StringUtils.hasText(toolKey)) {
                continue;
            }
            String normalizedTool = toolKey.trim();
            if (!toolRegistry.isValidKey(normalizedTool)) {
                throw new BusinessException(400, "Invalid tool: " + normalizedTool);
            }
            if (!normalized.contains(normalizedTool)) {
                normalized.add(normalizedTool);
            }
        }
        return normalized;
    }

    private String writeToolKeys(List<String> toolKeys) {
        try {
            return objectMapper.writeValueAsString(toolKeys == null ? List.of() : toolKeys);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize skill tools failed");
        }
    }

    private List<String> readToolKeys(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<AgentSkill> listDbSkills() {
        return agentSkillMapper.selectList(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getIsDeleted, false)
                .orderByAsc(AgentSkill::getCreatedAt)
                .orderByAsc(AgentSkill::getId));
    }

    private Map<String, AgentSkillDefinition> loadEnabledDefinitionMap() {
        Map<String, AgentSkillDefinition> merged = new LinkedHashMap<>(builtInDefinitions());
        List<AgentSkill> dbSkills = listDbSkills();
        for (AgentSkill dbSkill : dbSkills) {
            String key = normalizeKey(dbSkill.getSkillKey());
            if (!StringUtils.hasText(key)) {
                continue;
            }
            if (!Boolean.TRUE.equals(dbSkill.getEnabled())) {
                merged.remove(key);
                continue;
            }
            merged.put(key, new AgentSkillDefinition(
                    key,
                    StringUtils.hasText(dbSkill.getName()) ? dbSkill.getName().trim() : key,
                    trimOrNull(dbSkill.getDescription()),
                    readToolKeys(dbSkill.getToolKeys()),
                    normalizeExecutionMode(dbSkill.getExecutionMode()),
                    readInputSchema(dbSkill.getInputSchema()),
                    readStepConfig(dbSkill.getStepConfig(), readToolKeys(dbSkill.getToolKeys()))
            ));
        }
        return merged;
    }

    private Map<String, AgentSkillDefinition> builtInDefinitions() {
        Map<String, AgentSkillDefinition> definitions = new LinkedHashMap<>();
        registerBuiltIn(definitions, "web_research", "Web Research",
                "Search the web and summarize relevant findings.", List.of("web_search"), EXECUTION_MODE_SINGLE_TOOL,
                List.of(new SkillInputField("query", "string", true, "Search query")),
                List.of(new SkillStepDefinition("web_search", "Search the web for relevant sources and summarize the findings.")));
        registerBuiltIn(definitions, "time_lookup", "Time Lookup",
                "Look up the current time for a timezone or region.", List.of("datetime"), EXECUTION_MODE_SINGLE_TOOL,
                List.of(new SkillInputField("timezone", "string", false, "Timezone or region name")),
                List.of(new SkillStepDefinition("datetime", "Return the current time for the requested timezone or region.")));
        registerBuiltIn(definitions, "calculation", "Calculation",
                "Solve arithmetic and numeric expressions.", List.of("calculator"), EXECUTION_MODE_SINGLE_TOOL,
                List.of(new SkillInputField("expression", "string", true, "Math expression to solve")),
                List.of(new SkillStepDefinition("calculator", "Evaluate the numeric expression accurately.")));
        registerBuiltIn(definitions, "translation", "Translation",
                "Translate text between languages.", List.of("translation"), EXECUTION_MODE_SINGLE_TOOL,
                List.of(
                        new SkillInputField("text", "string", true, "Source text"),
                        new SkillInputField("targetLanguage", "string", true, "Target language")
                ),
                List.of(new SkillStepDefinition("translation", "Translate the source text into the target language.")));
        registerBuiltIn(definitions, "summarization", "Summarization",
                "Summarize long text into a concise answer.", List.of("summarize"), EXECUTION_MODE_SINGLE_TOOL,
                List.of(new SkillInputField("text", "string", true, "Text to summarize")),
                List.of(new SkillStepDefinition("summarize", "Summarize the provided text concisely.")));
        return definitions;
    }

    private void registerBuiltIn(Map<String, AgentSkillDefinition> definitions,
                                 String key,
                                 String name,
                                 String description,
                                 List<String> toolKeys,
                                 String executionMode,
                                 List<SkillInputField> inputSchema,
                                 List<SkillStepDefinition> stepConfig) {
        definitions.put(key, new AgentSkillDefinition(key, name, description, toolKeys, executionMode, inputSchema, stepConfig));
    }

    private SkillVO toVo(AgentSkill skill) {
        SkillVO vo = new SkillVO();
        vo.setKey(skill.getSkillKey());
        vo.setName(skill.getName());
        vo.setDescription(skill.getDescription());
        vo.setToolKeys(readToolKeys(skill.getToolKeys()));
        vo.setExecutionMode(normalizeExecutionMode(skill.getExecutionMode()));
        vo.setInputSchema(readInputSchema(skill.getInputSchema()));
        vo.setStepConfig(readStepConfig(skill.getStepConfig(), vo.getToolKeys()));
        vo.setEnabled(Boolean.TRUE.equals(skill.getEnabled()));
        vo.setCreatedAt(skill.getCreatedAt());
        vo.setUpdatedAt(skill.getUpdatedAt());
        return vo;
    }

    private String normalizeExecutionMode(String executionMode) {
        if (!StringUtils.hasText(executionMode)) {
            return EXECUTION_MODE_SINGLE_TOOL;
        }
        String normalized = executionMode.trim().toLowerCase(Locale.ROOT);
        if (!EXECUTION_MODE_SINGLE_TOOL.equals(normalized) && !EXECUTION_MODE_PIPELINE.equals(normalized)) {
            throw new BusinessException(400, "Unsupported execution mode: " + executionMode);
        }
        return normalized;
    }

    private String trimOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private List<SkillInputField> normalizeInputSchema(List<SkillInputField> inputSchema) {
        if (inputSchema == null) {
            return List.of();
        }
        List<SkillInputField> normalized = new ArrayList<>();
        for (SkillInputField field : inputSchema) {
            if (field == null || !StringUtils.hasText(field.getKey())) {
                continue;
            }
            String key = field.getKey().trim();
            if (!key.matches("[A-Za-z0-9_\\-]{1,64}")) {
                throw new BusinessException(400, "Invalid input field key: " + key);
            }
            String type = StringUtils.hasText(field.getType()) ? field.getType().trim().toLowerCase(Locale.ROOT) : "string";
            if (!List.of("string", "number", "boolean", "object", "array").contains(type)) {
                throw new BusinessException(400, "Invalid input field type: " + type);
            }
            normalized.add(new SkillInputField(
                    key,
                    type,
                    Boolean.TRUE.equals(field.getRequired()),
                    trimOrNull(field.getDescription())
            ));
        }
        return normalized;
    }

    private List<SkillStepDefinition> normalizeStepConfig(List<SkillStepDefinition> stepConfig, List<String> toolKeys) {
        List<String> safeToolKeys = toolKeys == null ? List.of() : toolKeys;
        if (stepConfig == null || stepConfig.isEmpty()) {
            return safeToolKeys.stream()
                    .map(toolKey -> new SkillStepDefinition(toolKey, null))
                    .toList();
        }
        List<SkillStepDefinition> normalized = new ArrayList<>();
        for (SkillStepDefinition step : stepConfig) {
            if (step == null || !StringUtils.hasText(step.getToolKey())) {
                continue;
            }
            String toolKey = step.getToolKey().trim();
            if (!safeToolKeys.contains(toolKey)) {
                throw new BusinessException(400, "Step tool must be selected in toolKeys: " + toolKey);
            }
            normalized.add(new SkillStepDefinition(toolKey, trimOrNull(step.getPrompt())));
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "Step config cannot be empty");
        }
        return normalized;
    }

    private String writeInputSchema(List<SkillInputField> inputSchema) {
        try {
            return objectMapper.writeValueAsString(inputSchema == null ? List.of() : inputSchema);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize input schema failed");
        }
    }

    private List<SkillInputField> readInputSchema(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SkillInputField>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeStepConfig(List<SkillStepDefinition> stepConfig) {
        try {
            return objectMapper.writeValueAsString(stepConfig == null ? List.of() : stepConfig);
        } catch (Exception e) {
            throw new BusinessException(500, "Serialize step config failed");
        }
    }

    private List<SkillStepDefinition> readStepConfig(String json, List<String> fallbackToolKeys) {
        if (!StringUtils.hasText(json)) {
            return (fallbackToolKeys == null ? List.<String>of() : fallbackToolKeys).stream()
                    .map(toolKey -> new SkillStepDefinition(toolKey, null))
                    .toList();
        }
        try {
            List<SkillStepDefinition> parsed = objectMapper.readValue(json, new TypeReference<List<SkillStepDefinition>>() {});
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return (fallbackToolKeys == null ? List.<String>of() : fallbackToolKeys).stream()
                    .map(toolKey -> new SkillStepDefinition(toolKey, null))
                    .toList();
        }
    }
}
