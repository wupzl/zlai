package com.harmony.backend.modules.chat.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.skill.SkillExecutionRequest;
import com.harmony.backend.ai.skill.SkillExecutionResult;
import com.harmony.backend.ai.skill.SkillPlan;
import com.harmony.backend.ai.skill.SkillPlanner;
import com.harmony.backend.ai.skill.SkillExecutor;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.prompt.ChatPromptService;
import com.harmony.backend.modules.chat.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ToolFollowupService {

    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final SkillExecutor skillExecutor;
    private final SkillPlanner skillPlanner;
    private final AgentToolRegistry toolRegistry;
    private final LlmAdapterRegistry adapterRegistry;
    private final AgentMemoryService agentMemoryService;
    private final ChatPromptService chatPromptService;
    private final BillingService billingService;

    public String handleToolCallIfNeeded(Session session,
                                         List<LlmMessage> messages,
                                         String assistantContent,
                                         String model,
                                         String assistantMessageId,
                                         ToolPolicy toolPolicy) {
        if (assistantContent == null || assistantContent.isBlank()) {
            return assistantContent;
        }
        ActionCall actionCall = parseActionCall(assistantContent);
        if (actionCall != null && actionCall.isSkillCall()) {
            List<String> allowedSkills = toolPolicy.getAllowedSkills(session);
            SkillPlan plan = skillPlanner.plan(extractLastUserPrompt(messages), actionCall.skill, actionCall.input, allowedSkills);
            if (plan == null || !plan.hasSelection()) {
                return handleDisallowedSkill(messages, assistantContent, model, actionCall.skill);
            }
            return executeSkillAndFollowup(session, plan, messages, assistantContent, model, assistantMessageId);
        }
        if (actionCall != null && actionCall.isToolCall()) {
            if (!toolPolicy.isToolAllowed(session, actionCall.tool)) {
                return handleDisallowedTool(messages, assistantContent, model, actionCall.tool);
            }
            return executeToolAndFollowup(session, actionCall.tool, actionCall.input, messages, assistantContent, model,
                    assistantMessageId);
        }
        return maybeRunToolByIntent(session, messages, assistantContent, model, assistantMessageId, toolPolicy);
    }

    public boolean isLikelyToolCall(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = normalizeToolCallJson(content);
        if (trimmed.startsWith("{") && (trimmed.contains("\"tool\"") || trimmed.contains("\"input\"")
                || trimmed.contains("\"skill\""))) {
            return true;
        }
        return extractActionJsonCandidate(trimmed) != null;
    }

    private String maybeRunToolByIntent(Session session,
                                        List<LlmMessage> messages,
                                        String assistantContent,
                                        String model,
                                        String assistantMessageId,
                                        ToolPolicy toolPolicy) {
        String userPrompt = extractLastUserPrompt(messages);
        if (userPrompt == null || userPrompt.isBlank()) {
            return assistantContent;
        }
        String intentTool = toolPolicy.detectToolIntent(session, userPrompt);
        if (intentTool == null) {
            return assistantContent;
        }
        String input = toolPolicy.buildToolInput(intentTool, userPrompt);
        return executeToolAndFollowup(session, intentTool, input, messages, assistantContent, model,
                assistantMessageId);
    }

    private String executeSkillAndFollowup(Session session,
                                           SkillPlan plan,
                                           List<LlmMessage> messages,
                                           String assistantContent,
                                           String model,
                                           String assistantMessageId) {
        String skillModel = resolveToolModel(session);
        String skillKey = plan.getSelectedSkillKey();
        String finalInput = plan.getNormalizedInput();
        log.info("Skill requested: requestedSkill={}, selectedSkill={}, reason={}",
                extractRequestedSkill(assistantContent), skillKey, plan.getReason());
        SkillExecutionResult result = skillExecutor.execute(new SkillExecutionRequest(skillKey, finalInput, skillModel));
        if (result == null || !result.isSuccess()) {
            String errorMsg = result != null && result.getError() != null
                    ? result.getError()
                    : "Skill execution failed";
            log.warn("Skill failed: skillKey={}, error={}", skillKey, errorMsg);
            return "Skill execution failed: " + errorMsg;
        }
        try {
            billingService.recordToolConsumption(session, assistantMessageId, result.getModel(),
                    result.getPromptTokens(), result.getCompletionTokens());
        } catch (Exception e) {
            log.warn("Skill billing failed: {}", e.getMessage());
            return "Skill execution failed: " + e.getMessage();
        }
        log.info("Skill success: skillKey={}, usedTools={}", skillKey, result.getUsedTools());
        if (session != null) {
            agentMemoryService.recordSkillExecution(session.getUserId(), session.getChatId(), skillKey, finalInput, result.getOutput(), assistantMessageId);
        }
        return followupWithResult(messages, assistantContent, model, result.getOutput());
    }

    private String executeToolAndFollowup(Session session,
                                          String toolKey,
                                          String input,
                                          List<LlmMessage> messages,
                                          String assistantContent,
                                          String model,
                                          String assistantMessageId) {
        String userPrompt = extractLastUserPrompt(messages);
        String finalInput = normalizeSearchInput(toolKey, input, userPrompt);
        log.info("Tool requested: toolKey={}, input={}", toolKey, finalInput);
        String toolModel = resolveToolModel(session);
        ToolExecutionResult result = toolExecutor.execute(new ToolExecutionRequest(toolKey, finalInput, toolModel));
        if (result == null || !result.isSuccess()) {
            String errorMsg = result != null && result.getError() != null
                    ? result.getError()
                    : "Tool execution failed";
            log.warn("Tool failed: toolKey={}, error={}", toolKey, errorMsg);
            if (isSearchTool(toolKey)) {
                ToolExecutionResult fallback = trySearchFallback(toolKey, finalInput, toolModel);
                if (fallback != null && fallback.isSuccess()) {
                    result = fallback;
                } else {
                    return answerWithoutSearch(messages, assistantContent, model);
                }
            } else {
                return "Tool execution failed: " + errorMsg;
            }
        }
        if (isSearchTool(toolKey) && isNoResults(result.getOutput())) {
            ToolExecutionResult fallback = trySearchFallback(toolKey, finalInput, toolModel);
            if (fallback != null && fallback.isSuccess() && !isNoResults(fallback.getOutput())) {
                result = fallback;
            } else {
                return answerWithoutSearch(messages, assistantContent, model);
            }
        }
        try {
            billingService.recordToolConsumption(session, assistantMessageId, result.getModel(),
                    result.getPromptTokens(), result.getCompletionTokens());
        } catch (Exception e) {
            log.warn("Tool billing failed: {}", e.getMessage());
            return "Tool execution failed: " + e.getMessage();
        }
        log.info("Tool success: toolKey={}, outputSize={}", toolKey,
                result.getOutput() == null ? 0 : result.getOutput().length());
        return followupWithResult(messages, assistantContent, model, result.getOutput());
    }

    private String followupWithResult(List<LlmMessage> messages,
                                      String assistantContent,
                                      String model,
                                      String resultOutput) {
        String userPrompt = extractLastUserPrompt(messages);
        boolean useChinese = preferChinese(userPrompt);
        String prefix = extractNonToolPrefix(assistantContent);
        List<LlmMessage> followup = new ArrayList<>(messages);
        followup.add(new LlmMessage("assistant", assistantContent));
        followup.add(new LlmMessage("user",
                chatPromptService.buildToolFollowupUserMessage(resultOutput, useChinese, prefix)));
        try {
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String followupAnswer = adapter.chat(followup, model);
            if (isLikelyToolCall(followupAnswer)) {
                String strictAnswer = forcePlainAnswerFromToolResult(messages, assistantContent, model, resultOutput);
                if (strictAnswer == null || strictAnswer.isBlank() || isLikelyToolCall(strictAnswer)) {
                    return buildSearchFallbackAnswer(resultOutput);
                }
                return strictAnswer;
            }
            if (followupAnswer == null || followupAnswer.isBlank()) {
                return buildSearchFallbackAnswer(resultOutput);
            }
            return followupAnswer;
        } catch (Exception e) {
            log.warn("Result followup failed: error={}", e.getMessage());
            return "Execution failed: " + e.getMessage();
        }
    }

    private String forcePlainAnswerFromToolResult(List<LlmMessage> messages,
                                                  String assistantContent,
                                                  String model,
                                                  String toolOutput) {
        try {
            String userPrompt = extractLastUserPrompt(messages);
            boolean useChinese = preferChinese(userPrompt);
            String prefix = extractNonToolPrefix(assistantContent);
            List<LlmMessage> strictFollowup = new ArrayList<>(messages);
            strictFollowup.add(new LlmMessage("assistant", assistantContent));
            strictFollowup.add(new LlmMessage("user",
                    chatPromptService.buildStrictToolAnswerUserMessage(toolOutput, useChinese, prefix)));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            return adapter.chat(strictFollowup, model);
        } catch (Exception e) {
            log.warn("Strict result followup failed: {}", e.getMessage());
            return null;
        }
    }

    private String answerWithoutSearch(List<LlmMessage> messages,
                                       String assistantContent,
                                       String model) {
        try {
            String userPrompt = extractLastUserPrompt(messages);
            if (!shouldAnswerWithoutSearch(userPrompt)) {
                return "Search results are unavailable for this time-sensitive question.";
            }
            List<LlmMessage> followup = new ArrayList<>(messages);
            followup.add(new LlmMessage("assistant", assistantContent));
            followup.add(new LlmMessage("user",
                    "Search results are unavailable. Answer the user from general knowledge without citing sources."));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String answer = adapter.chat(followup, model);
            return answer == null ? assistantContent : answer;
        } catch (Exception e) {
            log.warn("Fallback answer failed: {}", e.getMessage());
            return "Search results unavailable. Please try again later.";
        }
    }

    private boolean shouldAnswerWithoutSearch(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        String lower = prompt.toLowerCase();
        if (lower.matches(".*\\b20\\d{2}\\b.*")) {
            return false;
        }
        String[] timeSensitive = new String[] {
                "latest", "news", "announcement", "notice", "admission", "recruit",
                "policy", "official", "release", "ranking", "price", "exchange rate",
                "stock", "weather", "today", "now", "current", "breaking"
        };
        for (String token : timeSensitive) {
            if (lower.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private String handleDisallowedTool(List<LlmMessage> messages,
                                        String assistantContent,
                                        String model,
                                        String toolKey) {
        try {
            List<LlmMessage> followup = new ArrayList<>(messages);
            followup.add(new LlmMessage("assistant", assistantContent));
            String prompt = "The tool '" + toolKey + "' is not available for this agent. "
                    + "Answer the user directly without calling any tool.";
            followup.add(new LlmMessage("user", prompt));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String answer = adapter.chat(followup, model);
            return answer == null ? assistantContent : answer;
        } catch (Exception e) {
            log.warn("Disallowed tool fallback failed: {}", e.getMessage());
            return "Tool '" + toolKey + "' is not available. Please answer without tools.";
        }
    }

    private String handleDisallowedSkill(List<LlmMessage> messages,
                                         String assistantContent,
                                         String model,
                                         String skillKey) {
        try {
            List<LlmMessage> followup = new ArrayList<>(messages);
            followup.add(new LlmMessage("assistant", assistantContent));
            String prompt = "The skill '" + skillKey + "' is not available for this agent. "
                    + "Answer the user directly without calling any skill or tool.";
            followup.add(new LlmMessage("user", prompt));
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            String answer = adapter.chat(followup, model);
            return answer == null ? assistantContent : answer;
        } catch (Exception e) {
            log.warn("Disallowed skill fallback failed: {}", e.getMessage());
            return "Skill '" + skillKey + "' is not available. Please answer without skills.";
        }
    }

    private boolean isSearchTool(String toolKey) {
        return toolKey != null && "web_search".equals(toolKey.toLowerCase());
    }

    private boolean isNoResults(String output) {
        if (output == null) {
            return true;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return "No results found.".equalsIgnoreCase(trimmed)
                || trimmed.startsWith("Search tool returned");
    }

    private ToolExecutionResult trySearchFallback(String failedTool, String input, String toolModel) {
        List<String> order = List.of("web_search");
        String failed = failedTool == null ? "" : failedTool.toLowerCase();
        for (String key : order) {
            if (key.equals(failed)) {
                continue;
            }
            if (!toolRegistry.isValidKey(key)) {
                continue;
            }
            log.info("Tool fallback: {} -> {}", failedTool, key);
            ToolExecutionResult candidate = toolExecutor.execute(new ToolExecutionRequest(key, input, toolModel));
            if (candidate != null && candidate.isSuccess()
                    && candidate.getOutput() != null && !candidate.getOutput().isBlank()
                    && !"No results found.".equalsIgnoreCase(candidate.getOutput().trim())) {
                return candidate;
            }
        }
        return null;
    }

    private String resolveToolModel(Session session) {
        if (session != null && session.getToolModel() != null && !session.getToolModel().isBlank()) {
            return session.getToolModel().trim();
        }
        return null;
    }

    private String extractLastUserPrompt(List<LlmMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage msg = messages.get(i);
            if (msg != null && "user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    private String normalizeSearchInput(String toolKey, String input, String userPrompt) {
        if (input == null) {
            return null;
        }
        if (!"web_search".equalsIgnoreCase(toolKey)) {
            return input;
        }
        String prompt = userPrompt == null ? "" : userPrompt;
        if (prompt.matches(".*\\b20\\d{2}\\b.*")) {
            return input;
        }
        return input.replaceAll("\\b20\\d{2}\\b", String.valueOf(LocalDate.now().getYear()));
    }

    private ActionCall parseActionCall(String content) {
        String trimmed = normalizeToolCallJson(content);
        String candidate = extractActionJsonCandidate(trimmed);
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(candidate, new TypeReference<Map<String, Object>>() {});
            String tool = map.get("tool") == null ? null : map.get("tool").toString();
            String skill = map.get("skill") == null ? null : map.get("skill").toString();
            Object inputObj = map.get("input");
            String input;
            if (inputObj == null) {
                input = "";
            } else if (inputObj instanceof String) {
                input = (String) inputObj;
            } else {
                input = objectMapper.writeValueAsString(inputObj);
            }
            return new ActionCall(tool, skill, input);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeToolCallJson(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLf = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLf > 0 && lastFence > firstLf) {
                return trimmed.substring(firstLf + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractActionJsonCandidate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = trimmed.substring(start, i + 1).trim();
                        if (candidate.contains("\"tool\"") || candidate.contains("\"skill\"")) {
                            return candidate;
                        }
                        break;
                    }
                }
            }
            start = trimmed.indexOf('{', start + 1);
        }
        return null;
    }

    private String extractNonToolPrefix(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = normalizeToolCallJson(content);
        String candidate = extractActionJsonCandidate(normalized);
        if (candidate == null || candidate.isBlank()) {
            return normalized.trim();
        }
        int idx = normalized.indexOf(candidate);
        if (idx <= 0) {
            return "";
        }
        return normalized.substring(0, idx).trim();
    }

    private String extractRequestedSkill(String content) {
        ActionCall actionCall = parseActionCall(content);
        return actionCall == null ? null : actionCall.skill;
    }

    private boolean preferChinese(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private String buildSearchFallbackAnswer(String output) {
        String safe = output == null || output.isBlank() ? "No usable search result." : output;
        return "Search results:\n" + safe;
    }

    public interface ToolPolicy {
        String detectToolIntent(Session session, String prompt);

        String buildToolInput(String toolKey, String prompt);

        boolean isToolAllowed(Session session, String toolKey);

        List<String> getAllowedSkills(Session session);
    }

    private static class ActionCall {
        private final String tool;
        private final String skill;
        private final String input;

        private ActionCall(String tool, String skill, String input) {
            this.tool = tool;
            this.skill = skill;
            this.input = input;
        }

        private boolean isToolCall() {
            return tool != null && !tool.isBlank();
        }

        private boolean isSkillCall() {
            return skill != null && !skill.isBlank();
        }
    }
}
