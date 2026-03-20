package com.harmony.backend.ai.skill.service.impl;

import com.harmony.backend.ai.skill.AgentSkillDefinition;
import com.harmony.backend.ai.skill.SkillInputField;
import com.harmony.backend.ai.skill.SkillStepDefinition;
import com.harmony.backend.ai.skill.model.SkillMarkdownImportRequest;
import com.harmony.backend.ai.skill.model.SkillUpsertRequest;
import com.harmony.backend.ai.skill.model.SkillVO;
import com.harmony.backend.ai.skill.service.AgentSkillCatalogService;
import com.harmony.backend.ai.skill.service.SkillMarkdownImportService;
import com.harmony.backend.ai.tool.AgentToolDefinition;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SkillMarkdownImportServiceImpl implements SkillMarkdownImportService {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern META_PATTERN = Pattern.compile("^(?:[-*+]\\s*)?(key|name|title|description|tools?|tool keys|execution mode|execution_mode)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL_PATTERN = Pattern.compile("(?:tool|use)\\s*:\\s*([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_BULLET_PATTERN = Pattern.compile("^(?:[-*+]\\s+|\\d+[.)]\\s+)?`?([A-Za-z0-9_\\-]+)`?\\s*(?:\\(([^)]*)\\)|\\[([^]]*)])?\\s*(?::|-)?\\s*(.*)$");

    private final AgentSkillCatalogService agentSkillCatalogService;
    private final AgentToolRegistry toolRegistry;

    @Override
    public SkillVO importMarkdown(SkillMarkdownImportRequest request, Long adminId) {
        if (request == null || !StringUtils.hasText(request.getMarkdownContent())) {
            throw new BusinessException(400, "Markdown content is required");
        }
        ParsedMarkdownSkill parsed = parseMarkdown(request);
        SkillUpsertRequest upsertRequest = toUpsertRequest(parsed, request);

        SkillVO existingManaged = findManagedSkill(upsertRequest.getKey());
        if (existingManaged != null) {
            if (Boolean.TRUE.equals(request.getOverwriteExisting())) {
                return agentSkillCatalogService.update(existingManaged.getKey(), upsertRequest, adminId);
            }
            throw new BusinessException(400, "Skill key already exists: " + upsertRequest.getKey() + ". Set overwriteExisting=true to update it.");
        }

        AgentSkillDefinition existingEnabled = agentSkillCatalogService.getEnabledDefinition(upsertRequest.getKey());
        if (existingEnabled != null) {
            throw new BusinessException(400, "Cannot import over built-in or enabled skill key: " + upsertRequest.getKey());
        }

        return agentSkillCatalogService.create(upsertRequest, adminId);
    }

    private SkillUpsertRequest toUpsertRequest(ParsedMarkdownSkill parsed, SkillMarkdownImportRequest request) {
        SkillUpsertRequest upsertRequest = new SkillUpsertRequest();
        upsertRequest.setKey(parsed.key());
        upsertRequest.setName(parsed.name());
        upsertRequest.setDescription(parsed.description());
        upsertRequest.setToolKeys(parsed.toolKeys());
        upsertRequest.setExecutionMode(parsed.executionMode());
        upsertRequest.setInputSchema(parsed.inputSchema());
        upsertRequest.setStepConfig(parsed.stepConfig());
        upsertRequest.setEnabled(request.getEnabled());
        return upsertRequest;
    }

    private SkillVO findManagedSkill(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return agentSkillCatalogService.listManagedSkills().stream()
                .filter(skill -> key.equalsIgnoreCase(skill.getKey()))
                .findFirst()
                .orElse(null);
    }

    private ParsedMarkdownSkill parseMarkdown(SkillMarkdownImportRequest request) {
        String normalized = normalizeMarkdown(request.getMarkdownContent());
        FrontMatter frontMatter = extractFrontMatter(normalized);
        String body = frontMatter.body();
        List<Section> sections = parseSections(body);
        Set<String> validToolKeys = new LinkedHashSet<>();
        for (AgentToolDefinition tool : toolRegistry.listAll()) {
            if (tool != null && StringUtils.hasText(tool.getKey())) {
                validToolKeys.add(tool.getKey().trim());
            }
        }
        Map<String, String> aliases = normalizeAliases(request.getToolAliases(), validToolKeys);

        String title = firstNonBlank(
                frontMatter.values().get("title"),
                frontMatter.values().get("name"),
                findFirstHeading(body),
                stripExtension(request.getSourceName()),
                "Imported Skill"
        );
        String key = firstNonBlank(
                frontMatter.values().get("key"),
                extractMetadataValue(body, "key"),
                slugify(title),
                slugify(stripExtension(request.getSourceName()))
        );
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(400, "Unable to derive skill key from markdown");
        }

        String description = firstNonBlank(
                frontMatter.values().get("description"),
                extractMetadataValue(body, "description"),
                findSectionText(sections, "description", "summary", "overview"),
                extractFirstParagraph(body)
        );

        List<String> toolKeys = parseToolKeys(frontMatter, body, sections, aliases, validToolKeys, request.getDefaultToolKeys());
        if (toolKeys.isEmpty()) {
            throw new BusinessException(400, "No tool keys found in markdown. Add a Tools section or provide defaultToolKeys.");
        }

        List<SkillInputField> inputSchema = parseInputSchema(findSectionText(sections, "inputs", "input", "parameters", "arguments"));
        List<SkillStepDefinition> stepConfig = parseStepConfig(findSectionText(sections, "steps", "workflow", "process", "procedure"), aliases, validToolKeys);
        if (stepConfig.isEmpty()) {
            stepConfig = null;
        }

        String executionMode = firstNonBlank(
                request.getDefaultExecutionMode(),
                frontMatter.values().get("execution_mode"),
                extractMetadataValue(body, "execution mode"),
                inferExecutionMode(toolKeys, stepConfig)
        );

        return new ParsedMarkdownSkill(
                key,
                title.trim(),
                trimToNull(description),
                toolKeys,
                normalizeExecutionMode(executionMode),
                inputSchema,
                stepConfig
        );
    }

    private String normalizeMarkdown(String markdown) {
        return markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
    }

    private FrontMatter extractFrontMatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return new FrontMatter(Map.of(), markdown);
        }
        int end = markdown.indexOf("\n---\n", 4);
        if (end < 0) {
            return new FrontMatter(Map.of(), markdown);
        }
        String frontMatterText = markdown.substring(4, end).trim();
        String body = markdown.substring(end + 5).trim();
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        String currentListKey = null;
        List<String> currentItems = new ArrayList<>();
        for (String rawLine : frontMatterText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("- ") && currentListKey != null) {
                currentItems.add(line.substring(2).trim());
                values.put(currentListKey, String.join(", ", currentItems));
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            currentListKey = line.substring(0, idx).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            currentItems = new ArrayList<>();
            String value = line.substring(idx + 1).trim();
            value = trimQuotes(value);
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            values.put(currentListKey, value);
        }
        return new FrontMatter(values, body);
    }

    private List<Section> parseSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();
        for (String line : markdown.split("\n")) {
            Matcher matcher = HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                if (currentHeading != null) {
                    sections.add(new Section(currentHeading, currentBody.toString().trim()));
                }
                currentHeading = matcher.group(2).trim();
                currentBody.setLength(0);
                continue;
            }
            if (currentHeading != null) {
                currentBody.append(line).append('\n');
            }
        }
        if (currentHeading != null) {
            sections.add(new Section(currentHeading, currentBody.toString().trim()));
        }
        return sections;
    }

    private String findFirstHeading(String markdown) {
        for (String line : markdown.split("\n")) {
            Matcher matcher = HEADING_PATTERN.matcher(line.trim());
            if (matcher.matches() && "#".equals(matcher.group(1))) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    private String extractMetadataValue(String markdown, String wantedKey) {
        if (!StringUtils.hasText(markdown) || !StringUtils.hasText(wantedKey)) {
            return null;
        }
        String normalizedWanted = wantedKey.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        for (String line : markdown.split("\n")) {
            Matcher matcher = META_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            String foundKey = matcher.group(1).trim().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (normalizedWanted.equals(foundKey)) {
                return trimQuotes(matcher.group(2).trim());
            }
        }
        return null;
    }

    private String findSectionText(List<Section> sections, String... candidates) {
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        Set<String> wanted = Arrays.stream(candidates)
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (Section section : sections) {
            String heading = section.heading().toLowerCase(Locale.ROOT);
            if (wanted.contains(heading)) {
                return section.body();
            }
        }
        return null;
    }

    private List<String> parseToolKeys(FrontMatter frontMatter,
                                       String body,
                                       List<Section> sections,
                                       Map<String, String> aliases,
                                       Set<String> validToolKeys,
                                       List<String> defaultToolKeys) {
        LinkedHashSet<String> toolKeys = new LinkedHashSet<>();
        addToolCandidates(toolKeys, frontMatter.values().get("tools"), aliases, validToolKeys);
        addToolCandidates(toolKeys, frontMatter.values().get("tool_keys"), aliases, validToolKeys);
        addToolCandidates(toolKeys, extractMetadataValue(body, "tools"), aliases, validToolKeys);
        addToolCandidates(toolKeys, extractMetadataValue(body, "tool keys"), aliases, validToolKeys);
        addToolCandidates(toolKeys, findSectionText(sections, "tools", "tooling", "capabilities"), aliases, validToolKeys);
        addToolCandidates(toolKeys, findSectionText(sections, "steps", "workflow", "process", "procedure"), aliases, validToolKeys);
        if (defaultToolKeys != null) {
            for (String toolKey : defaultToolKeys) {
                String resolved = resolveToolKey(toolKey, aliases, validToolKeys);
                if (resolved != null) {
                    toolKeys.add(resolved);
                }
            }
        }
        return new ArrayList<>(toolKeys);
    }

    private void addToolCandidates(Set<String> toolKeys,
                                   String text,
                                   Map<String, String> aliases,
                                   Set<String> validToolKeys) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher codeMatcher = CODE_PATTERN.matcher(text);
        while (codeMatcher.find()) {
            String resolved = resolveToolKey(codeMatcher.group(1), aliases, validToolKeys);
            if (resolved != null) {
                toolKeys.add(resolved);
            }
        }
        Matcher toolMatcher = TOOL_PATTERN.matcher(text);
        while (toolMatcher.find()) {
            String resolved = resolveToolKey(toolMatcher.group(1), aliases, validToolKeys);
            if (resolved != null) {
                toolKeys.add(resolved);
            }
        }
        for (String part : text.split("[,\\n|]")) {
            String resolved = resolveToolKey(part, aliases, validToolKeys);
            if (resolved != null) {
                toolKeys.add(resolved);
            }
        }
        for (String validToolKey : validToolKeys) {
            if (containsToken(text, validToolKey)) {
                toolKeys.add(validToolKey);
            }
        }
    }

    private List<SkillInputField> parseInputSchema(String sectionText) {
        if (!StringUtils.hasText(sectionText)) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SkillInputField> fields = new ArrayList<>();
        for (String rawLine : sectionText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            SkillInputField fromTable = parseInputTableRow(line);
            if (fromTable != null && seen.add(fromTable.getKey())) {
                fields.add(fromTable);
                continue;
            }
            SkillInputField fromBullet = parseInputBullet(line);
            if (fromBullet != null && seen.add(fromBullet.getKey())) {
                fields.add(fromBullet);
            }
        }
        return fields;
    }

    private SkillInputField parseInputTableRow(String line) {
        if (!line.startsWith("|") || line.replace("|", "").replace("-", "").replace(":", "").trim().isEmpty()) {
            return null;
        }
        String[] columns = Arrays.stream(line.split("\\|"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
        if (columns.length < 2) {
            return null;
        }
        String key = sanitizeFieldKey(columns[0]);
        if (!StringUtils.hasText(key) || "field".equalsIgnoreCase(key) || "name".equalsIgnoreCase(key)) {
            return null;
        }
        String type = normalizeFieldType(columns.length > 1 ? columns[1] : null);
        boolean required = columns.length > 2 && isRequiredToken(columns[2]);
        String description = columns.length > 3 ? trimToNull(columns[3]) : null;
        return new SkillInputField(key, type, required, description);
    }

    private SkillInputField parseInputBullet(String line) {
        Matcher matcher = INPUT_BULLET_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String key = sanitizeFieldKey(matcher.group(1));
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String qualifiers = firstNonBlank(matcher.group(2), matcher.group(3));
        String type = normalizeFieldType(qualifiers);
        boolean required = isRequiredToken(qualifiers) || line.toLowerCase(Locale.ROOT).contains("required");
        String description = trimToNull(matcher.group(4));
        return new SkillInputField(key, type, required, description);
    }

    private List<SkillStepDefinition> parseStepConfig(String sectionText,
                                                      Map<String, String> aliases,
                                                      Set<String> validToolKeys) {
        if (!StringUtils.hasText(sectionText)) {
            return List.of();
        }
        List<SkillStepDefinition> steps = new ArrayList<>();
        for (String rawLine : sectionText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String toolKey = extractToolKeyFromLine(line, aliases, validToolKeys);
            if (toolKey == null) {
                continue;
            }
            String prompt = trimToNull(stripLinePrefix(line)
                    .replaceAll("(?i)(tool|use)\\s*:\\s*" + Pattern.quote(toolKey), "")
                    .replace('`', ' ')
                    .replaceAll("\\s+", " "));
            steps.add(new SkillStepDefinition(toolKey, prompt));
        }
        return steps;
    }

    private String extractToolKeyFromLine(String line,
                                          Map<String, String> aliases,
                                          Set<String> validToolKeys) {
        Matcher toolMatcher = TOOL_PATTERN.matcher(line);
        if (toolMatcher.find()) {
            String resolved = resolveToolKey(toolMatcher.group(1), aliases, validToolKeys);
            if (resolved != null) {
                return resolved;
            }
        }
        Matcher codeMatcher = CODE_PATTERN.matcher(line);
        while (codeMatcher.find()) {
            String resolved = resolveToolKey(codeMatcher.group(1), aliases, validToolKeys);
            if (resolved != null) {
                return resolved;
            }
        }
        for (String validToolKey : validToolKeys) {
            if (containsToken(line, validToolKey)) {
                return validToolKey;
            }
        }
        return null;
    }

    private Map<String, String> normalizeAliases(Map<String, String> rawAliases, Set<String> validToolKeys) {
        java.util.LinkedHashMap<String, String> aliases = new java.util.LinkedHashMap<>();
        if (rawAliases == null || rawAliases.isEmpty()) {
            return aliases;
        }
        for (Map.Entry<String, String> entry : rawAliases.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            String resolved = resolveToolKey(entry.getValue(), Map.of(), validToolKeys);
            if (resolved != null) {
                aliases.put(entry.getKey().trim().toLowerCase(Locale.ROOT), resolved);
            }
        }
        return aliases;
    }

    private String resolveToolKey(String candidate,
                                  Map<String, String> aliases,
                                  Set<String> validToolKeys) {
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        String normalized = stripDecorators(candidate).trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (aliases.containsKey(normalized)) {
            return aliases.get(normalized);
        }
        return validToolKeys.contains(normalized) ? normalized : null;
    }

    private String inferExecutionMode(List<String> toolKeys, List<SkillStepDefinition> stepConfig) {
        if (stepConfig != null && stepConfig.size() > 1) {
            return "pipeline";
        }
        if (toolKeys != null && toolKeys.size() > 1) {
            return "pipeline";
        }
        return "single_tool";
    }

    private String normalizeExecutionMode(String executionMode) {
        if (!StringUtils.hasText(executionMode)) {
            return "single_tool";
        }
        String normalized = executionMode.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return "pipeline".equals(normalized) ? "pipeline" : "single_tool";
    }

    private String extractFirstParagraph(String body) {
        StringBuilder sb = new StringBuilder();
        boolean pastTitle = false;
        for (String rawLine : body.split("\n")) {
            String line = rawLine.trim();
            if (!pastTitle && line.startsWith("# ")) {
                pastTitle = true;
                continue;
            }
            if (line.isEmpty()) {
                if (sb.length() > 0) {
                    break;
                }
                continue;
            }
            if (HEADING_PATTERN.matcher(line).matches() || META_PATTERN.matcher(line).matches()) {
                continue;
            }
            sb.append(line).append(' ');
        }
        return trimToNull(sb.toString());
    }

    private boolean containsToken(String text, String token) {
        return Pattern.compile("(?i)(^|[^A-Za-z0-9_\\-])" + Pattern.quote(token) + "($|[^A-Za-z0-9_\\-])")
                .matcher(text)
                .find();
    }

    private String sanitizeFieldKey(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String cleaned = stripDecorators(value).trim();
        return cleaned.matches("[A-Za-z0-9_\\-]{1,64}") ? cleaned : null;
    }

    private String normalizeFieldType(String value) {
        if (!StringUtils.hasText(value)) {
            return "string";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("number") || normalized.contains("int") || normalized.contains("float") || normalized.contains("double")) {
            return "number";
        }
        if (normalized.contains("bool")) {
            return "boolean";
        }
        if (normalized.contains("array") || normalized.contains("list")) {
            return "array";
        }
        if (normalized.contains("object") || normalized.contains("json") || normalized.contains("map")) {
            return "object";
        }
        return "string";
    }

    private boolean isRequiredToken(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("required") || normalized.contains("yes") || normalized.contains("true");
    }

    private String stripExtension(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String fileName = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String slugify(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64).replaceAll("_+$", "");
        }
        if (normalized.length() < 2) {
            return null;
        }
        return normalized;
    }

    private String stripDecorators(String value) {
        return value == null ? "" : value.replace("`", "").replace("*", "").replace("_", "_").replace("[", "").replace("]", "");
    }

    private String stripLinePrefix(String line) {
        return line.replaceFirst("^(?:[-*+]\\s+|\\d+[.)]\\s+)", "").trim();
    }

    private String trimQuotes(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record FrontMatter(Map<String, String> values, String body) {}

    private record Section(String heading, String body) {}

    private record ParsedMarkdownSkill(String key,
                                      String name,
                                      String description,
                                      List<String> toolKeys,
                                      String executionMode,
                                      List<SkillInputField> inputSchema,
                                      List<SkillStepDefinition> stepConfig) {}
}


