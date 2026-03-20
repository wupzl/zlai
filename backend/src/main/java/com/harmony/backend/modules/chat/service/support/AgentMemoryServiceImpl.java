package com.harmony.backend.modules.chat.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.common.entity.ConversationSummary;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.TaskState;
import com.harmony.backend.common.entity.UserMemory;
import com.harmony.backend.common.mapper.ConversationSummaryMapper;
import com.harmony.backend.common.mapper.MessageMapper;
import com.harmony.backend.common.mapper.TaskStateMapper;
import com.harmony.backend.common.mapper.UserMemoryMapper;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final Set<String> USER_MEMORY_SIGNAL_KEYWORDS = Set.of(
            "answer in chinese", "answer in english", "please use chinese", "please use english",
            "\u4e2d\u6587\u56de\u7b54", "\u82f1\u6587\u56de\u7b54", "\u8bf7\u7528\u4e2d\u6587", "\u8bf7\u7528\u82f1\u6587",
            "timezone", "time zone", "asia/shanghai", "beijing time", "\u5317\u4eac\u65f6\u95f4", "\u4e2d\u56fd\u65f6\u95f4",
            "prefer", "preference", "\u98ce\u683c", "\u8bed\u6c14", "\u7b80\u6d01", "\u8be6\u7ec6", "\u6b63\u5f0f", "\u53e3\u8bed"
    );

    private static final Set<String> TASK_SIGNAL_KEYWORDS = Set.of(
            "\u6b65\u9aa4", "\u8ba1\u5212", "todo", "\u5f85\u529e", "\u4efb\u52a1", "\u4e0b\u4e00\u6b65", "\u65b9\u6848", "\u5b9e\u73b0", "\u6392\u67e5", "\u5206\u6790", "\u6574\u7406",
            "\u603b\u7ed3", "\u5bf9\u6bd4", "\u8f93\u51fa", "\u751f\u6210", "\u4fee\u6539", "\u4f18\u5316", "\u590d\u4e60", "\u51c6\u5907", "\u5b89\u6392", "\u5b8c\u6210",
            "step", "plan", "task", "next step", "implement", "fix", "analyze", "summarize",
            "compare", "prepare", "review", "optimize", "refactor"
    );

    private final ConversationSummaryMapper conversationSummaryMapper;
    private final UserMemoryMapper userMemoryMapper;
    private final TaskStateMapper taskStateMapper;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final LlmAdapterRegistry adapterRegistry;

    @Value("${app.memory.summary-model:deepseek-chat}")
    private String summaryModel;

    @Value("${app.memory.summary-refresh-every-messages:6}")
    private int summaryRefreshEveryMessages;

    @Value("${app.memory.summary-min-refresh-minutes:15}")
    private int summaryMinRefreshMinutes;

    @Value("${app.memory.user-memory-model-enabled:true}")
    private boolean userMemoryModelEnabled;

    @Value("${app.memory.user-memory-min-refresh-minutes:180}")
    private int userMemoryMinRefreshMinutes;

    @Value("${app.memory.user-memory-min-prompt-length:18}")
    private int userMemoryMinPromptLength;

    @Value("${app.memory.task-state-model-enabled:true}")
    private boolean taskStateModelEnabled;

    @Value("${app.memory.task-state-min-refresh-seconds:45}")
    private int taskStateMinRefreshSeconds;

    @Override
    public String buildMemoryContext(Long userId, String chatId) {
        List<String> sections = new ArrayList<>();

        ConversationSummary summary = findConversationSummary(chatId);
        if (summary != null && StringUtils.hasText(summary.getSummary())) {
            sections.add("Conversation summary:\n" + summary.getSummary().trim());
        }

        List<UserMemory> memories = listUserMemories(userId);
        if (!memories.isEmpty()) {
            StringBuilder sb = new StringBuilder("User memory:\n");
            for (UserMemory memory : memories) {
                sb.append("- ")
                        .append(memory.getMemoryKey())
                        .append(": ")
                        .append(memory.getMemoryValue())
                        .append('\n');
            }
            sections.add(sb.toString().trim());
        }

        TaskState taskState = findActiveTaskState(chatId);
        if (taskState != null) {
            StringBuilder sb = new StringBuilder("Task state:\n");
            if (StringUtils.hasText(taskState.getGoal())) {
                sb.append("- goal: ").append(taskState.getGoal()).append('\n');
            }
            if (StringUtils.hasText(taskState.getCurrentSkill())) {
                sb.append("- current_skill: ").append(taskState.getCurrentSkill()).append('\n');
            }
            if (StringUtils.hasText(taskState.getCurrentStep())) {
                sb.append("- current_step: ").append(taskState.getCurrentStep()).append('\n');
            }
            if (StringUtils.hasText(taskState.getStatus())) {
                sb.append("- status: ").append(taskState.getStatus()).append('\n');
            }
            appendTaskArtifacts(sb, taskState.getArtifactsJson());
            sections.add(sb.toString().trim());
        }

        if (sections.isEmpty()) {
            return "";
        }
        return String.join("\n\n", sections);
    }

    @Override
    public void updateMemoryAfterTurn(Long userId, String chatId, String userPrompt, String assistantContent, String assistantMessageId) {
        try {
            maybeUpsertConversationSummary(userId, chatId, assistantMessageId);
            maybeInferAndUpsertUserMemory(userId, userPrompt);
            maybeUpsertAnsweredTaskState(userId, chatId, userPrompt, assistantContent, assistantMessageId);
        } catch (Exception e) {
            log.warn("Memory update failed: chatId={}, userId={}, error={}", chatId, userId, e.getMessage());
        }
    }

    @Override
    public void recordSkillExecution(Long userId,
                                     String chatId,
                                     String skillKey,
                                     String skillInput,
                                     String skillOutput,
                                     String assistantMessageId) {
        try {
            TaskState taskState = findOrCreateTaskState(userId, chatId, assistantMessageId);
            taskState.setCurrentSkill(skillKey);
            taskState.setCurrentStep("skill_executed");
            taskState.setStatus("ACTIVE");
            taskState.setArtifactsJson(writeSkillArtifacts(skillKey, skillInput, skillOutput));
            taskState.setLastMessageId(assistantMessageId);
            saveTaskState(taskState);
        } catch (Exception e) {
            log.warn("Skill memory update failed: chatId={}, userId={}, skillKey={}, error={}", chatId, userId, skillKey, e.getMessage());
        }
    }

    @Override
    public void recordWorkflowProgress(Long userId,
                                       String chatId,
                                       String workflowKey,
                                       String goal,
                                       String currentStep,
                                       String status,
                                       String artifactsJson,
                                       String assistantMessageId) {
        try {
            TaskState taskState = findOrCreateTaskState(userId, chatId, assistantMessageId);
            if (StringUtils.hasText(workflowKey)) {
                taskState.setTaskKey(workflowKey.trim());
            }
            if (StringUtils.hasText(goal)) {
                taskState.setGoal(compact(goal, 220));
            }
            if (StringUtils.hasText(currentStep)) {
                taskState.setCurrentStep(currentStep.trim());
            }
            if (StringUtils.hasText(status)) {
                taskState.setStatus(status.trim());
            }
            if (artifactsJson != null) {
                taskState.setArtifactsJson(artifactsJson);
            }
            taskState.setLastMessageId(assistantMessageId);
            saveTaskState(taskState);
        } catch (Exception e) {
            log.warn("Workflow memory update failed: chatId={}, userId={}, workflowKey={}, error={}", chatId, userId, workflowKey, e.getMessage());
        }
    }

    private void maybeUpsertConversationSummary(Long userId, String chatId, String lastMessageId) {
        if (!StringUtils.hasText(chatId)) {
            return;
        }
        ConversationSummary existing = findConversationSummary(chatId);
        List<Message> recent = listRecentMessages(chatId, Math.max(8, summaryRefreshEveryMessages * 3));
        if (recent.isEmpty() || !shouldRefreshSummary(existing, recent)) {
            return;
        }
        String summaryText = buildSummaryText(existing, recent);
        if (!StringUtils.hasText(summaryText)) {
            return;
        }
        if (existing == null) {
            conversationSummaryMapper.insert(ConversationSummary.builder()
                    .chatId(chatId)
                    .userId(userId)
                    .summary(summaryText)
                    .lastMessageId(lastMessageId)
                    .build());
            return;
        }
        if (normalizedEquals(existing.getSummary(), summaryText) && Objects.equals(existing.getLastMessageId(), lastMessageId)) {
            return;
        }
        existing.setUserId(userId);
        existing.setSummary(summaryText);
        existing.setLastMessageId(lastMessageId);
        conversationSummaryMapper.updateById(existing);
    }

    private boolean shouldRefreshSummary(ConversationSummary existing, List<Message> recent) {
        if (existing == null || !StringUtils.hasText(existing.getLastMessageId())) {
            return true;
        }
        int delta = 0;
        boolean found = false;
        for (Message message : recent) {
            if (message == null) {
                continue;
            }
            if (existing.getLastMessageId().equals(message.getMessageId())) {
                found = true;
                break;
            }
            delta++;
        }
        if (!found) {
            return true;
        }
        int requiredDelta = Math.max(1, summaryRefreshEveryMessages);
        if (delta < requiredDelta) {
            return false;
        }
        if (isCooldownElapsed(existing.getUpdatedAt(), Duration.ofMinutes(Math.max(1, summaryMinRefreshMinutes)))) {
            return true;
        }
        return delta >= requiredDelta * 2;
    }

    private String buildSummaryText(ConversationSummary existing, List<Message> recent) {
        List<Message> ordered = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            ordered.add(recent.get(i));
        }
        String llmSummary = summarizeWithModel(existing, ordered);
        if (StringUtils.hasText(llmSummary)) {
            return llmSummary.trim();
        }
        return fallbackSummary(existing, ordered);
    }

    private String summarizeWithModel(ConversationSummary existing, List<Message> ordered) {
        try {
            LlmAdapter adapter = adapterRegistry.getAdapter(summaryModel);
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(new LlmMessage("system", "You maintain concise conversation memory. Summarize durable goals, decisions, constraints, preferences, and open items. Output plain text with short bullet points only."));
            StringBuilder user = new StringBuilder();
            if (existing != null && StringUtils.hasText(existing.getSummary())) {
                user.append("Existing summary:\n").append(existing.getSummary().trim()).append("\n\n");
            }
            user.append("Recent conversation:\n");
            for (Message message : ordered) {
                if (message == null || !StringUtils.hasText(message.getContent())) {
                    continue;
                }
                if ("system".equalsIgnoreCase(message.getRole())) {
                    continue;
                }
                String role = "assistant".equalsIgnoreCase(message.getRole()) ? "Assistant" : "User";
                user.append(role).append(": ").append(compact(message.getContent(), 220)).append('\n');
            }
            messages.add(new LlmMessage("user", user.toString()));
            String response = adapter.chat(messages, summaryModel);
            return StringUtils.hasText(response) ? compact(response, 1600) : null;
        } catch (Exception e) {
            log.warn("Model summary failed: {}", e.getMessage());
            return null;
        }
    }

    private String fallbackSummary(ConversationSummary existing, List<Message> ordered) {
        StringBuilder summaryText = new StringBuilder();
        if (existing != null && StringUtils.hasText(existing.getSummary())) {
            summaryText.append(existing.getSummary().trim());
        }
        for (Message message : ordered) {
            if (message == null || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            if ("system".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "Assistant" : "User";
            String compact = compact(message.getContent(), 180);
            if (!StringUtils.hasText(compact)) {
                continue;
            }
            if (summaryText.length() > 0) {
                summaryText.append('\n');
            }
            summaryText.append("- ").append(role).append(": ").append(compact);
            if (summaryText.length() >= 1600) {
                break;
            }
        }
        return summaryText.toString();
    }

    private void maybeInferAndUpsertUserMemory(Long userId, String userPrompt) {
        if (userId == null || !StringUtils.hasText(userPrompt)) {
            return;
        }
        String normalizedPrompt = normalizeForCompare(userPrompt);
        if (normalizedPrompt.length() < Math.max(1, userMemoryMinPromptLength) || !containsUserMemorySignal(normalizedPrompt)) {
            return;
        }
        if (userMemoryModelEnabled) {
            Map<String, String> extracted = extractUserMemoryWithModel(userPrompt);
            if (!extracted.isEmpty()) {
                for (Map.Entry<String, String> entry : extracted.entrySet()) {
                    if (!StringUtils.hasText(entry.getValue())) {
                        continue;
                    }
                    upsertUserMemoryIfNeeded(userId, entry.getKey(), entry.getValue().trim(), 0.75, "model_inference");
                }
            }
        }
        if (normalizedPrompt.contains("answer in chinese") || normalizedPrompt.contains("\u4e2d\u6587\u56de\u7b54") || normalizedPrompt.contains("\u8bf7\u7528\u4e2d\u6587")) {
            upsertUserMemoryIfNeeded(userId, "preferred_language", "zh-CN", 0.9, "chat_inference");
        }
        if (normalizedPrompt.contains("answer in english") || normalizedPrompt.contains("\u82f1\u6587\u56de\u7b54") || normalizedPrompt.contains("\u8bf7\u7528\u82f1\u6587")) {
            upsertUserMemoryIfNeeded(userId, "preferred_language", "en-US", 0.9, "chat_inference");
        }
        if (normalizedPrompt.contains("asia/shanghai") || normalizedPrompt.contains("beijing time")
                || normalizedPrompt.contains("\u5317\u4eac\u65f6\u95f4") || normalizedPrompt.contains("\u4e2d\u56fd\u65f6\u95f4")) {
            upsertUserMemoryIfNeeded(userId, "timezone", "Asia/Shanghai", 0.8, "chat_inference");
        }
        if (normalizedPrompt.contains("\u7b80\u6d01") || normalizedPrompt.contains("concise") || normalizedPrompt.contains("brief")) {
            upsertUserMemoryIfNeeded(userId, "response_style", "concise", 0.7, "chat_inference");
        }
        if (normalizedPrompt.contains("\u8be6\u7ec6") || normalizedPrompt.contains("detailed") || normalizedPrompt.contains("\u5c55\u5f00")) {
            upsertUserMemoryIfNeeded(userId, "response_style", "detailed", 0.7, "chat_inference");
        }
    }

    private boolean containsUserMemorySignal(String normalizedPrompt) {
        for (String keyword : USER_MEMORY_SIGNAL_KEYWORDS) {
            if (normalizedPrompt.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> extractUserMemoryWithModel(String userPrompt) {
        try {
            LlmAdapter adapter = adapterRegistry.getAdapter(summaryModel);
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(new LlmMessage("system", "Extract only stable user preferences from the message. Output JSON only. Allowed keys: preferred_language, timezone, response_style. Omit unknown values."));
            messages.add(new LlmMessage("user", userPrompt));
            String response = adapter.chat(messages, summaryModel);
            if (!StringUtils.hasText(response)) {
                return Map.of();
            }
            String json = extractJsonObject(response);
            if (!StringUtils.hasText(json)) {
                return Map.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            copyStringValue(parsed, result, "preferred_language");
            copyStringValue(parsed, result, "timezone");
            copyStringValue(parsed, result, "response_style");
            return result;
        } catch (Exception e) {
            log.warn("User memory model extraction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private void maybeUpsertAnsweredTaskState(Long userId, String chatId, String userPrompt, String assistantContent, String lastMessageId) {
        if (!StringUtils.hasText(chatId)) {
            return;
        }
        TaskState existing = findActiveTaskState(chatId);
        boolean taskLikeTurn = isTaskLikeTurn(userPrompt, assistantContent);
        Map<String, Object> artifacts = buildResponseArtifacts(userPrompt, assistantContent, taskLikeTurn);
        boolean structuredArtifacts = hasStructuredTaskArtifacts(artifacts);
        if (existing == null && !taskLikeTurn && !structuredArtifacts) {
            return;
        }

        String goal = compact(userPrompt, 220);
        String artifactsJson = writeArtifactsJson(artifacts);
        TaskState taskState = existing != null ? existing : findOrCreateTaskState(userId, chatId, lastMessageId);
        taskState.setGoal(goal);
        if (!StringUtils.hasText(taskState.getCurrentSkill())) {
            taskState.setCurrentSkill(null);
        }
        taskState.setCurrentStep("answered");
        taskState.setStatus("ACTIVE");
        taskState.setLastMessageId(lastMessageId);

        if (!shouldPersistAnsweredTaskState(existing, taskState, artifactsJson, structuredArtifacts, taskLikeTurn)) {
            return;
        }
        taskState.setArtifactsJson(artifactsJson);
        saveTaskState(taskState);
    }

    private boolean isTaskLikeTurn(String userPrompt, String assistantContent) {
        String merged = normalizeForCompare((userPrompt == null ? "" : userPrompt) + "\n" + (assistantContent == null ? "" : assistantContent));
        for (String keyword : TASK_SIGNAL_KEYWORDS) {
            if (merged.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildResponseArtifacts(String userPrompt, String assistantContent, boolean taskLikeTurn) {
        Map<String, Object> artifacts = new LinkedHashMap<>(extractTaskArtifactsWithModel(userPrompt, assistantContent));
        if (artifacts.isEmpty() && !taskLikeTurn) {
            return artifacts;
        }
        if (StringUtils.hasText(assistantContent)) {
            artifacts.putIfAbsent("last_assistant_excerpt", compact(assistantContent, 240));
        }
        return artifacts;
    }

    private boolean hasStructuredTaskArtifacts(Map<String, Object> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return false;
        }
        return hasNonEmptyList(artifacts.get("done_items"))
                || hasNonEmptyList(artifacts.get("pending_actions"))
                || hasNonEmptyList(artifacts.get("open_questions"));
    }

    private boolean hasNonEmptyList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPersistAnsweredTaskState(TaskState existing,
                                                   TaskState proposed,
                                                   String artifactsJson,
                                                   boolean structuredArtifacts,
                                                   boolean taskLikeTurn) {
        if (existing == null) {
            return true;
        }
        boolean importantChanged = !normalizedEquals(existing.getGoal(), proposed.getGoal())
                || !normalizedEquals(existing.getCurrentSkill(), proposed.getCurrentSkill())
                || !normalizedEquals(existing.getCurrentStep(), proposed.getCurrentStep())
                || !normalizedEquals(existing.getStatus(), proposed.getStatus());
        if (importantChanged) {
            return true;
        }
        boolean artifactsChanged = !normalizedEquals(existing.getArtifactsJson(), artifactsJson);
        if (!artifactsChanged) {
            return false;
        }
        if (structuredArtifacts) {
            return true;
        }
        return taskLikeTurn && isCooldownElapsed(existing.getUpdatedAt(), Duration.ofSeconds(Math.max(1, taskStateMinRefreshSeconds)));
    }

    private TaskState findOrCreateTaskState(Long userId, String chatId, String lastMessageId) {
        TaskState existing = findActiveTaskState(chatId);
        if (existing != null) {
            existing.setUserId(userId);
            existing.setLastMessageId(lastMessageId);
            return existing;
        }
        return TaskState.builder()
                .chatId(chatId)
                .userId(userId)
                .taskKey("default")
                .status("ACTIVE")
                .lastMessageId(lastMessageId)
                .build();
    }

    private void saveTaskState(TaskState taskState) {
        if (taskState.getId() == null) {
            taskStateMapper.insert(taskState);
            return;
        }
        taskStateMapper.updateById(taskState);
    }

    private ConversationSummary findConversationSummary(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return null;
        }
        return conversationSummaryMapper.selectOne(new LambdaQueryWrapper<ConversationSummary>()
                .eq(ConversationSummary::getChatId, chatId)
                .eq(ConversationSummary::getIsDeleted, false)
                .last("limit 1"));
    }

    private List<Message> listRecentMessages(String chatId, int limit) {
        if (!StringUtils.hasText(chatId)) {
            return List.of();
        }
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getChatId, chatId)
                .eq(Message::getIsDeleted, false)
                .orderByDesc(Message::getCreatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    private List<UserMemory> listUserMemories(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getIsDeleted, false)
                .orderByDesc(UserMemory::getUpdatedAt)
                .last("limit 8"));
    }

    private TaskState findActiveTaskState(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return null;
        }
        return taskStateMapper.selectOne(new LambdaQueryWrapper<TaskState>()
                .eq(TaskState::getChatId, chatId)
                .eq(TaskState::getIsDeleted, false)
                .orderByDesc(TaskState::getUpdatedAt)
                .last("limit 1"));
    }

    private void upsertUserMemoryIfNeeded(Long userId, String key, String value, double confidence, String source) {
        UserMemory existing = findUserMemory(userId, key);
        if (existing == null) {
            userMemoryMapper.insert(UserMemory.builder()
                    .userId(userId)
                    .memoryKey(key)
                    .memoryValue(value)
                    .confidence(confidence)
                    .source(source)
                    .build());
            return;
        }

        boolean valueChanged = !normalizedEquals(existing.getMemoryValue(), value);
        boolean strongerConfidence = existing.getConfidence() == null || confidence > existing.getConfidence() + 0.001d;
        boolean cooldownElapsed = isCooldownElapsed(existing.getUpdatedAt(), Duration.ofMinutes(Math.max(1, userMemoryMinRefreshMinutes)));
        if (!valueChanged && !strongerConfidence && !cooldownElapsed) {
            return;
        }
        existing.setMemoryValue(value);
        existing.setConfidence(confidence);
        existing.setSource(source);
        userMemoryMapper.updateById(existing);
    }

    private UserMemory findUserMemory(Long userId, String key) {
        return userMemoryMapper.selectOne(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getMemoryKey, key)
                .eq(UserMemory::getIsDeleted, false)
                .last("limit 1"));
    }

    private Map<String, Object> extractTaskArtifactsWithModel(String userPrompt, String assistantContent) {
        if (!taskStateModelEnabled || !StringUtils.hasText(assistantContent)) {
            return Map.of();
        }
        try {
            LlmAdapter adapter = adapterRegistry.getAdapter(summaryModel);
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(new LlmMessage("system", "Extract task-state fields from the assistant reply. Output JSON only with keys done_items, pending_actions, open_questions. Each value must be an array of short strings. Omit empty fields if unknown."));
            StringBuilder user = new StringBuilder();
            if (StringUtils.hasText(userPrompt)) {
                user.append("User request:\n").append(compact(userPrompt, 240)).append("\n\n");
            }
            user.append("Assistant reply:\n").append(compact(assistantContent, 500));
            messages.add(new LlmMessage("user", user.toString()));
            String response = adapter.chat(messages, summaryModel);
            String json = extractJsonObject(response);
            if (!StringUtils.hasText(json)) {
                return Map.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Map<String, Object> result = new LinkedHashMap<>();
            copyStringListValue(parsed, result, "done_items");
            copyStringListValue(parsed, result, "pending_actions");
            copyStringListValue(parsed, result, "open_questions");
            return result;
        } catch (Exception e) {
            log.warn("Task artifact model extraction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private void appendTaskArtifacts(StringBuilder sb, String artifactsJson) {
        if (!StringUtils.hasText(artifactsJson)) {
            return;
        }
        try {
            Map<String, Object> artifacts = objectMapper.readValue(artifactsJson, Map.class);
            appendArtifactList(sb, artifacts, "done_items");
            appendArtifactList(sb, artifacts, "pending_actions");
            appendArtifactList(sb, artifacts, "open_questions");
            Object excerpt = artifacts.get("last_assistant_excerpt");
            if (excerpt != null && StringUtils.hasText(String.valueOf(excerpt))) {
                sb.append("- last_assistant_excerpt: ").append(String.valueOf(excerpt).trim()).append('\n');
            }
            Object skill = artifacts.get("skill");
            if (skill != null && StringUtils.hasText(String.valueOf(skill))) {
                sb.append("- skill_artifact: ").append(String.valueOf(skill).trim()).append('\n');
            }
        } catch (Exception e) {
            sb.append("- artifacts: ").append(artifactsJson).append('\n');
        }
    }

    private void appendArtifactList(StringBuilder sb, Map<String, Object> artifacts, String key) {
        if (artifacts == null || !artifacts.containsKey(key) || artifacts.get(key) == null) {
            return;
        }
        Object value = artifacts.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            sb.append("- ").append(key).append(": ");
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
            if (!parts.isEmpty()) {
                sb.append(String.join(" | ", parts)).append('\n');
            }
        }
    }

    private void copyStringListValue(Map<String, Object> parsed, Map<String, Object> result, String key) {
        if (parsed == null || result == null || !parsed.containsKey(key) || parsed.get(key) == null) {
            return;
        }
        Object raw = parsed.get(key);
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isEmpty() && !"unknown".equalsIgnoreCase(text) && !"null".equalsIgnoreCase(text)) {
                    values.add(text);
                }
            }
        }
        if (!values.isEmpty()) {
            result.put(key, values);
        }
    }

    private void copyStringValue(Map<String, Object> parsed, Map<String, String> result, String key) {
        if (parsed == null || result == null || !parsed.containsKey(key) || parsed.get(key) == null) {
            return;
        }
        String value = String.valueOf(parsed.get(key)).trim();
        if (!value.isEmpty() && !"null".equalsIgnoreCase(value) && !"unknown".equalsIgnoreCase(value)) {
            result.put(key, value);
        }
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private String writeArtifactsJson(Map<String, Object> artifacts) {
        try {
            return objectMapper.writeValueAsString(artifacts == null ? Map.of() : artifacts);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String writeSkillArtifacts(String skillKey, String skillInput, String skillOutput) {
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("skill", skillKey == null ? "" : skillKey);
        artifacts.put("skill_input", compact(skillInput, 220));
        artifacts.put("skill_output_excerpt", compact(skillOutput, 260));
        artifacts.put("done_items", List.of("Executed skill: " + (skillKey == null ? "" : skillKey)));
        artifacts.put("pending_actions", List.of());
        artifacts.put("open_questions", List.of());
        return writeArtifactsJson(artifacts);
    }

    private boolean isCooldownElapsed(LocalDateTime updatedAt, Duration duration) {
        if (updatedAt == null || duration == null || duration.isZero() || duration.isNegative()) {
            return true;
        }
        LocalDateTime deadline = updatedAt.plus(duration);
        return !deadline.isAfter(LocalDateTime.now());
    }

    private boolean normalizedEquals(String left, String right) {
        return Objects.equals(normalizeForCompare(left), normalizeForCompare(right));
    }

    private String normalizeForCompare(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String compact(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String compact = text.replaceAll("\s+", " ").trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, maxLen).trim() + "...";
    }
}
