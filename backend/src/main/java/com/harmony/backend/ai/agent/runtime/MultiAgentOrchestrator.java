package com.harmony.backend.ai.agent.runtime;

import com.harmony.backend.ai.agent.model.TeamAgentRuntime;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class MultiAgentOrchestrator {

    private final Executor agentExecutor;
    private final long agentTimeoutSeconds;

    public MultiAgentOrchestrator(@Qualifier("agentExecutor") Executor agentExecutor,
                                  @Value("${app.agents.multiagent-timeout-seconds:20}") long agentTimeoutSeconds) {
        this.agentExecutor = agentExecutor;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
    }

    public String run(List<LlmMessage> contextMessages, String model, LlmAdapter adapter) {
        CompletableFuture<String> planner = supplyWithTimeout(() ->
                safe(adapter.chat(buildPlannerMessages(contextMessages), model)), "planner");
        CompletableFuture<String> researcher = supplyWithTimeout(() ->
                safe(adapter.chat(buildResearcherMessages(contextMessages), model)), "researcher");
        CompletableFuture<String> critic = supplyWithTimeout(() ->
                safe(adapter.chat(buildCriticMessages(contextMessages), model)), "critic");

        String plan = planner.join();
        String research = researcher.join();
        String critique = critic.join();

        List<LlmMessage> aggregatorMessages = new ArrayList<>();
        aggregatorMessages.add(new LlmMessage("system",
                "You are Aggregator Agent. Combine the plan, research notes, and critique into a final answer. " +
                        "Ensure correctness and clarity. Output only the final answer."));
        aggregatorMessages.addAll(contextMessages);
        aggregatorMessages.add(new LlmMessage("assistant",
                "Plan:\n" + plan + "\n\nResearch:\n" + research + "\n\nCritique:\n" + critique));
        String finalAnswer = safe(adapter.chat(aggregatorMessages, model));
        if (finalAnswer.isBlank()) {
            return fallback(plan, research, critique);
        }
        return finalAnswer;
    }

    public Flux<String> stream(List<LlmMessage> contextMessages, String model, LlmAdapter adapter) {
        CompletableFuture<String> planner = supplyWithTimeout(() ->
                safe(adapter.chat(buildPlannerMessages(contextMessages), model)), "planner");
        CompletableFuture<String> researcher = supplyWithTimeout(() ->
                safe(adapter.chat(buildResearcherMessages(contextMessages), model)), "researcher");
        CompletableFuture<String> critic = supplyWithTimeout(() ->
                safe(adapter.chat(buildCriticMessages(contextMessages), model)), "critic");

        return Mono.zip(
                Mono.fromFuture(planner),
                Mono.fromFuture(researcher),
                Mono.fromFuture(critic)
        ).flatMapMany(tuple -> {
            String plan = tuple.getT1();
            String research = tuple.getT2();
            String critique = tuple.getT3();

            List<LlmMessage> aggregatorMessages = new ArrayList<>();
            aggregatorMessages.add(new LlmMessage("system",
                    "You are Aggregator Agent. Combine the plan, research notes, and critique into a final answer. " +
                            "Ensure correctness and clarity. Output only the final answer."));
            aggregatorMessages.addAll(contextMessages);
            aggregatorMessages.add(new LlmMessage("assistant",
                    "Plan:\n" + plan + "\n\nResearch:\n" + research + "\n\nCritique:\n" + critique));
            return adapter.streamChat(aggregatorMessages, model);
        });
    }

    public String runTeam(List<LlmMessage> contextMessages,
                          String defaultModel,
                          Agent manager,
                          List<TeamAgentRuntime> teamAgents,
                          LlmAdapterRegistry adapterRegistry,
                          ToolExecutor toolExecutor,
                          ToolUsageRecorder usageRecorder) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            LlmAdapter adapter = adapterRegistry.getAdapter(defaultModel);
            return run(contextMessages, defaultModel, adapter);
        }
        String userPrompt = extractLastUserPrompt(contextMessages);
        ToolContext toolContext = null;
        if (toolExecutor != null) {
            toolContext = resolveForcedTool(userPrompt, teamAgents, manager, toolExecutor, usageRecorder);
        }
        final ToolContext finalToolContext = toolContext;

        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<TeamAgentRuntime> runList = new ArrayList<>();
        for (TeamAgentRuntime runtime : teamAgents) {
            if (runtime == null || runtime.getAgent() == null) {
                continue;
            }
            Agent agent = runtime.getAgent();
            String model = (agent.getModel() == null || agent.getModel().isBlank())
                    ? defaultModel
                    : agent.getModel();
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            runList.add(runtime);
            futures.add(supplyWithTimeout(() -> {
                if (finalToolContext == null && toolExecutor != null && shouldForceTool(userPrompt, runtime)) {
                    String forcedTool = chooseForcedTool(userPrompt, runtime);
                    if (forcedTool != null) {
                        String input = buildToolInput(forcedTool, userPrompt);
                        return runToolFollowup(runtime, toolExecutor, adapter, model,
                                contextMessages, usageRecorder, null, forcedTool, input);
                    }
                }
                String draft = safe(adapter.chat(buildTeamMessages(runtime, contextMessages, finalToolContext), model));
                if (toolExecutor != null) {
                    String maybeTool = tryToolCall(draft, runtime, toolExecutor, adapter, model, contextMessages, usageRecorder);
                    return maybeTool != null ? maybeTool : draft;
                }
                return draft;
            }, "team-agent-" + agent.getId()));
        }
        List<String> outputs = new ArrayList<>();
        for (CompletableFuture<String> future : futures) {
            outputs.add(future.join());
        }
        for (int i = 0; i < outputs.size(); i++) {
            TeamAgentRuntime runtime = runList.get(i);
            Agent agent = runtime != null ? runtime.getAgent() : null;
            String name = agent != null && agent.getName() != null ? agent.getName() : "Agent-" + (i + 1);
            String text = outputs.get(i) == null ? "" : outputs.get(i);
            String snippet = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            log.info("Team agent output: {} -> {}", name, snippet.replaceAll("\\s+", " "));
        }

        String managerSystem = manager != null && manager.getInstructions() != null
                ? manager.getInstructions()
                : "You are Manager Agent. Aggregate team outputs into a final answer.";
        String managerModel = manager != null && manager.getModel() != null && !manager.getModel().isBlank()
                ? manager.getModel()
                : defaultModel;
        LlmAdapter managerAdapter = adapterRegistry.getAdapter(managerModel);

        List<LlmMessage> aggregatorMessages = new ArrayList<>();
        String managerHint = "You will receive multiple team outputs. ";
        if (finalToolContext != null && !finalToolContext.failed) {
            managerHint += "Use tool results as the single source of truth and ignore contradictions.";
        } else if (finalToolContext != null && finalToolContext.failed) {
            managerHint += "Search failed. Answer using general knowledge and clearly state that no sources were found.";
        }
        aggregatorMessages.add(new LlmMessage("system", managerSystem + "\n" + managerHint));
        aggregatorMessages.addAll(contextMessages);
        if (finalToolContext != null && finalToolContext.output != null && !finalToolContext.output.isBlank()) {
            aggregatorMessages.add(new LlmMessage("system",
                    "Tool result (authoritative):\n" + finalToolContext.output));
        }
        aggregatorMessages.add(new LlmMessage("assistant", formatTeamOutputs(runList, outputs)));
        return safe(managerAdapter.chat(aggregatorMessages, managerModel));
    }

    public Flux<String> streamTeam(List<LlmMessage> contextMessages,
                                   String defaultModel,
                                   Agent manager,
                                   List<TeamAgentRuntime> teamAgents,
                                   LlmAdapterRegistry adapterRegistry,
                                   ToolExecutor toolExecutor,
                                   ToolUsageRecorder usageRecorder) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            LlmAdapter adapter = adapterRegistry.getAdapter(defaultModel);
            return stream(contextMessages, defaultModel, adapter);
        }
        String userPrompt = extractLastUserPrompt(contextMessages);
        ToolContext toolContext = null;
        if (toolExecutor != null) {
            toolContext = resolveForcedTool(userPrompt, teamAgents, manager, toolExecutor, usageRecorder);
        }
        final ToolContext finalToolContext = toolContext;

        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<TeamAgentRuntime> runList = new ArrayList<>();
        for (TeamAgentRuntime runtime : teamAgents) {
            if (runtime == null || runtime.getAgent() == null) {
                continue;
            }
            Agent agent = runtime.getAgent();
            String model = (agent.getModel() == null || agent.getModel().isBlank())
                    ? defaultModel
                    : agent.getModel();
            LlmAdapter adapter = adapterRegistry.getAdapter(model);
            runList.add(runtime);
            futures.add(supplyWithTimeout(() -> {
                if (finalToolContext == null && toolExecutor != null && shouldForceTool(userPrompt, runtime)) {
                    String forcedTool = chooseForcedTool(userPrompt, runtime);
                    if (forcedTool != null) {
                        String input = buildToolInput(forcedTool, userPrompt);
                        return runToolFollowup(runtime, toolExecutor, adapter, model,
                                contextMessages, usageRecorder, null, forcedTool, input);
                    }
                }
                String draft = safe(adapter.chat(buildTeamMessages(runtime, contextMessages, finalToolContext), model));
                if (toolExecutor != null) {
                    String maybeTool = tryToolCall(draft, runtime, toolExecutor, adapter, model, contextMessages, usageRecorder);
                    return maybeTool != null ? maybeTool : draft;
                }
                return draft;
            }, "team-agent-" + agent.getId()));
        }

        return Flux.fromIterable(futures)
                .flatMap(Mono::fromFuture)
                .collectList()
                .flatMapMany(outputs -> {
                    for (int i = 0; i < outputs.size(); i++) {
                        TeamAgentRuntime runtime = runList.get(i);
                        Agent agent = runtime != null ? runtime.getAgent() : null;
                        String name = agent != null && agent.getName() != null ? agent.getName() : "Agent-" + (i + 1);
                        String text = outputs.get(i) == null ? "" : outputs.get(i);
                        String snippet = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                        log.info("Team agent output (stream): {} -> {}", name, snippet.replaceAll("\\s+", " "));
                    }
                    String managerSystem = manager != null && manager.getInstructions() != null
                            ? manager.getInstructions()
                            : "You are Manager Agent. Aggregate team outputs into a final answer.";
                    String managerModel = manager != null && manager.getModel() != null && !manager.getModel().isBlank()
                            ? manager.getModel()
                            : defaultModel;
                    LlmAdapter managerAdapter = adapterRegistry.getAdapter(managerModel);

                    List<LlmMessage> aggregatorMessages = new ArrayList<>();
                    String managerHint = "You will receive multiple team outputs. ";
                    if (finalToolContext != null && !finalToolContext.failed) {
                        managerHint += "Use tool results as the single source of truth and ignore contradictions.";
                    } else if (finalToolContext != null && finalToolContext.failed) {
                        managerHint += "Search failed. Answer using general knowledge and clearly state that no sources were found.";
                    }
                    aggregatorMessages.add(new LlmMessage("system", managerSystem + "\n" + managerHint));
                    aggregatorMessages.addAll(contextMessages);
                    if (finalToolContext != null && finalToolContext.output != null && !finalToolContext.output.isBlank()) {
                        aggregatorMessages.add(new LlmMessage("system",
                                "Tool result (authoritative):\n" + finalToolContext.output));
                    }
                    aggregatorMessages.add(new LlmMessage("assistant", formatTeamOutputs(runList, outputs)));
                    return managerAdapter.streamChat(aggregatorMessages, managerModel);
                });
    }

    private List<String> splitForStream(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        int step = 24;
        for (int i = 0; i < text.length(); i += step) {
            chunks.add(text.substring(i, Math.min(i + step, text.length())));
        }
        return chunks;
    }

    private CompletableFuture<String> supplyWithTimeout(Supplier<String> supplier, String label) {
        return CompletableFuture
                .supplyAsync(supplier, agentExecutor)
                .orTimeout(agentTimeoutSeconds, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Agent task timeout/failure: label={}, error={}", label, ex == null ? "unknown" : ex.getMessage());
                    return "";
                });
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private List<LlmMessage> buildTeamMessages(TeamAgentRuntime runtime,
                                               List<LlmMessage> contextMessages,
                                               ToolContext toolContext) {
        Agent agent = runtime.getAgent();
        List<LlmMessage> messages = new ArrayList<>();
        String system = agent.getInstructions();
        if (system == null || system.isBlank()) {
            system = "You are Specialist Agent. Provide a concise expert answer.";
        }
        if (runtime.getRole() != null && !runtime.getRole().isBlank()) {
            system = "Role: " + runtime.getRole().trim() + "\n" + system;
        }
        if (runtime.getTools() != null && !runtime.getTools().isEmpty()) {
            system = system + "\n\nAllowed tools: " + String.join(", ", runtime.getTools())
                    + ". If you need a tool, respond ONLY with JSON: {\"tool\":\"<key>\",\"input\":\"...\"}.";
        }
        messages.add(new LlmMessage("system", system));
        if (toolContext != null && toolContext.output != null && !toolContext.output.isBlank()) {
            messages.add(new LlmMessage("system",
                    "Tool result (authoritative):\n" + toolContext.output
                            + "\nYou must base your response strictly on this result and never invent values."));
        }
        messages.addAll(contextMessages);
        return messages;
    }

    private String formatTeamOutputs(List<TeamAgentRuntime> agents, List<String> outputs) {
        StringBuilder sb = new StringBuilder();
        int size = Math.min(agents.size(), outputs.size());
        for (int i = 0; i < size; i++) {
            TeamAgentRuntime runtime = agents.get(i);
            Agent agent = runtime != null ? runtime.getAgent() : null;
            String name = agent != null && agent.getName() != null ? agent.getName() : "Agent-" + (i + 1);
            sb.append(name).append(":\n");
            sb.append(outputs.get(i) == null ? "" : outputs.get(i)).append("\n\n");
        }
        return sb.toString().trim();
    }

    @FunctionalInterface
    public interface ToolUsageRecorder {
        void record(String model, int promptTokens, int completionTokens);
    }

    private String tryToolCall(String draft,
                               TeamAgentRuntime runtime,
                               ToolExecutor toolExecutor,
                               LlmAdapter adapter,
                               String model,
                               List<LlmMessage> contextMessages,
                               ToolUsageRecorder usageRecorder) {
        if (draft == null) {
            return null;
        }
        String trimmed = draft.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = mapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object toolObj = map.get("tool");
            String tool = toolObj == null ? null : toolObj.toString();
            if (tool == null || tool.isBlank()) {
                return null;
            }
            if (!isToolAllowed(runtime, tool)) {
                return null;
            }
            Object inputObj = map.get("input");
            String input;
            if (inputObj == null) {
                input = "";
            } else if (inputObj instanceof String) {
                input = (String) inputObj;
            } else {
                input = mapper.writeValueAsString(inputObj);
            }
            return runToolFollowup(runtime, toolExecutor, adapter, model, contextMessages, usageRecorder, draft, tool, input);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isToolAllowed(TeamAgentRuntime runtime, String tool) {
        if (runtime == null || tool == null || tool.isBlank()) {
            return false;
        }
        if (runtime.getTools() == null || runtime.getTools().isEmpty()) {
            return false;
        }
        return runtime.getTools().stream().anyMatch(t -> t.equalsIgnoreCase(tool));
    }

    private String extractLastUserPrompt(List<LlmMessage> contextMessages) {
        if (contextMessages == null) {
            return null;
        }
        for (int i = contextMessages.size() - 1; i >= 0; i--) {
            LlmMessage msg = contextMessages.get(i);
            if (msg != null && "user".equalsIgnoreCase(msg.getRole())) {
                return msg.getContent();
            }
        }
        return null;
    }

    private String detectToolIntent(String prompt, TeamAgentRuntime runtime) {
        if (prompt == null || runtime == null) {
            return null;
        }
        List<String> tools = runtime.getTools();
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        String lower = prompt.toLowerCase();
        boolean explicitSearch = prompt.contains("\u641c\u7d22") || prompt.contains("\u67e5\u8be2") || prompt.contains("\u67e5\u627e")
                || lower.contains("search") || lower.contains("engine") || lower.contains("baidu");
        boolean timeQuery = isTimeQuery(prompt, lower);
        boolean searchQuery = explicitSearch || isSearchQuery(prompt, lower);
        if (searchQuery) {
            if (tools.stream().anyMatch(t -> "web_search".equalsIgnoreCase(t))) {
                return "web_search";
            }
        }
        if (timeQuery && tools.stream().anyMatch(t -> "datetime".equalsIgnoreCase(t))) {
            return "datetime";
        }
        return null;
    }

    private boolean shouldForceTool(String prompt, TeamAgentRuntime runtime) {
        if (prompt == null || runtime == null) {
            return false;
        }
        List<String> tools = runtime.getTools();
        if (tools == null || tools.isEmpty()) {
            return false;
        }
        String lower = prompt.toLowerCase();
        boolean timeQuery = isTimeQuery(prompt, lower);
        boolean searchQuery = isSearchQuery(prompt, lower) || prompt.contains("\u641c\u7d22") || prompt.contains("\u67e5\u8be2");
        if (timeQuery && tools.stream().anyMatch(t -> "datetime".equalsIgnoreCase(t))) {
            return true;
        }
        return searchQuery && tools.stream().anyMatch(t ->
                "web_search".equalsIgnoreCase(t));
    }

    private String chooseForcedTool(String prompt, TeamAgentRuntime runtime) {
        if (prompt == null || runtime == null) {
            return null;
        }
        List<String> tools = runtime.getTools();
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        String lower = prompt.toLowerCase();
        boolean timeQuery = isTimeQuery(prompt, lower);
        if (timeQuery && tools.stream().anyMatch(t -> "datetime".equalsIgnoreCase(t))) {
            return "datetime";
        }
        if (tools.stream().anyMatch(t -> "web_search".equalsIgnoreCase(t))) {
            return "web_search";
        }
        return null;
    }

    private String chooseForcedToolForTeam(String prompt, List<TeamAgentRuntime> teamAgents) {
        if (prompt == null || teamAgents == null || teamAgents.isEmpty()) {
            return null;
        }
        if (teamHasTool(teamAgents, "web_search")) {
            return "web_search";
        }
        if (isTimeQuery(prompt, prompt.toLowerCase()) && teamHasTool(teamAgents, "datetime")) {
            return "datetime";
        }
        return null;
    }

    private boolean teamHasTool(List<TeamAgentRuntime> teamAgents, String tool) {
        if (teamAgents == null || teamAgents.isEmpty()) {
            return false;
        }
        for (TeamAgentRuntime runtime : teamAgents) {
            if (runtime == null || runtime.getTools() == null) {
                continue;
            }
            for (String t : runtime.getTools()) {
                if (t != null && t.equalsIgnoreCase(tool)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTimeQuery(String original, String lower) {
        if (original == null) {
            return false;
        }
        return lower.contains("time")
                || lower.contains("now")
                || original.contains("\u65f6\u95f4")
                || original.contains("\u51e0\u70b9")
                || original.contains("\u5317\u4eac\u65f6\u95f4")
                || original.contains("\u4e0a\u6d77")
                || original.contains("\u5317\u4eac")
                || original.contains("\u4e2d\u56fd");
    }

    private boolean isSearchQuery(String original, String lower) {
        if (original == null) {
            return false;
        }
        return original.contains("\u641c\u7d22")
                || original.contains("\u67e5\u8be2")
                || original.contains("\u67e5\u627e")
                || lower.contains("search")
                || lower.contains("news")
                || original.contains("\u65b0\u95fb")
                || original.contains("\u65f6\u4e8b");
    }

    private String buildToolInput(String toolKey, String prompt) {
        if ("datetime".equalsIgnoreCase(toolKey)) {
            if (prompt != null && (prompt.contains("\u4e0a\u6d77") || prompt.contains("\u5317\u4eac") || prompt.contains("\u4e2d\u56fd") || prompt.contains("\u5317\u4eac\u65f6\u95f4"))) {
                return "Asia/Shanghai";
            }
            return "UTC";
        }
        return prompt == null ? "" : prompt;
    }

    private String normalizeSearchInput(String input, String userPrompt) {
        if (input == null) {
            return null;
        }
        if (userPrompt == null) {
            return input;
        }
        boolean userSpecifiedYear = userPrompt.matches(".*\\b20\\d{2}\\b.*");
        if (userSpecifiedYear) {
            return input;
        }
        return input.replaceAll("\\b20\\d{2}\\b", String.valueOf(java.time.LocalDate.now().getYear()));
    }

    private String buildSearchQuery(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "";
        }
        String raw = userPrompt;
        String q = raw;
        q = q.replaceAll("[\\u201c\\u201d\\\"'\\u2018\\u2019]", " ");
        q = q.replaceAll("\\u641c\\u7d22\\u5f15\\u64ce", " ");
        q = q.replaceAll("\\u8981\\u6c42\\s*[:\\uFF1A]?", " ");
        q = q.replaceAll("\\u8bf7\\u89e3\\u91ca", " ");
        q = q.replaceAll("\\u7ed9\\u6211", " ");
        q = q.replaceAll("\\u7ed9\\u51fa", " ");
        q = q.replaceAll("\\u8be6\\u7ec6", " ");
        q = q.replaceAll("\\u6d41\\u7a0b", " ");
        q = q.replaceAll("\\u6b65\\u9aa4", " ");
        q = q.replaceAll("\\u6700\\u540e", " ");
        q = q.replaceAll("\\u5e38\\u89c1", " ");
        q = q.replaceAll("\\u538b\\u7f29", " ");
        q = q.replaceAll("\\u5b57", " ");
        q = q.replaceAll("\\u7ed3\\u8bba", " ");
        q = q.replaceAll("\\u6765\\u6e90", " ");
        q = q.replaceAll("\\u6211\\u5728\\u505a", " ");
        q = q.replaceAll("[()\\uFF08\\uFF09,\\uFF0C.\\u3002;\\uFF1B:\\uFF1A\\u3001/\\\\|]", " ");
        q = q.replaceAll("\\d+", " ");
        q = q.replaceAll("\\s+", " ").trim();

        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("[\\u201c\\u201d\\\"']([^\\u201c\\u201d\\\"']+)[\\u201c\\u201d\\\"']").matcher(raw);
        while (quoted.find()) {
            String t = quoted.group(1).trim();
            if (t.length() > 1) {
                tokens.add(t);
            }
        }
        java.util.regex.Matcher en = java.util.regex.Pattern.compile("[A-Za-z0-9\\-]{3,}").matcher(q);
        while (en.find() && tokens.size() < 8) {
            String t = en.group().trim();
            if (!tokens.contains(t)) {
                tokens.add(t);
            }
        }
        String[] parts = q.split(" ");
        for (String part : parts) {
            if (tokens.size() >= 8) {
                break;
            }
            String t = part.trim();
            if (t.length() <= 1) {
                continue;
            }
            if (!tokens.contains(t)) {
                tokens.add(t);
            }
        }
        String condensed = tokens.isEmpty() ? q : String.join(" ", tokens);
        condensed = condensed.replaceAll("[^A-Za-z0-9\\u4E00-\\u9FFF ]", " ");
        condensed = condensed.replaceAll("\\s+", " ").trim();
        if (condensed.isBlank()) {
            condensed = q;
        }
        if (condensed.length() > 80) {
            condensed = condensed.substring(0, 80);
        }
        return normalizeSearchInput(condensed, raw);
    }

    private String runToolFollowup(TeamAgentRuntime runtime,
                                   ToolExecutor toolExecutor,
                                   LlmAdapter adapter,
                                   String model,
                                   List<LlmMessage> contextMessages,
                                   ToolUsageRecorder usageRecorder,
                                   String draft,
                                   String tool,
                                   String input) {
        if (toolExecutor == null) {
            return null;
        }
        String toolModel = runtime.getAgent() != null ? runtime.getAgent().getToolModel() : null;
        String finalInput = input;
        if ("web_search".equalsIgnoreCase(tool)) {
            finalInput = buildSearchQuery(extractLastUserPrompt(contextMessages));
        }
        log.info("Team agent tool requested: toolKey={}, input={}", tool, finalInput);
        ToolExecutionResult result = toolExecutor.execute(new ToolExecutionRequest(tool, finalInput, toolModel));
        if (result == null || !result.isSuccess()) {
            return "Tool execution failed: " + (result != null && result.getError() != null ? result.getError() : "unknown error");
        }
        String output = result.getOutput() == null ? "" : result.getOutput().trim();
        boolean noResults = output.isBlank() || "No results found.".equalsIgnoreCase(output)
                || output.toLowerCase().contains("blocked") || output.startsWith("Search tool returned");
        if (noResults) {
            return answerWithoutSearch(contextMessages, adapter, model);
        }
        if (usageRecorder != null && result.getPromptTokens() != null && result.getCompletionTokens() != null) {
            usageRecorder.record(result.getModel(), result.getPromptTokens(), result.getCompletionTokens());
        }
        log.info("Team agent tool success: toolKey={}, outputSize={}", tool, output.length());
        List<LlmMessage> followup = new ArrayList<>();
        followup.addAll(contextMessages);
        if (draft != null && !draft.isBlank()) {
            followup.add(new LlmMessage("assistant", draft));
        }
        followup.add(new LlmMessage("system",
                "You must use tool results as the single source of truth. Do NOT invent data. " +
                        "If the tool output is empty or indicates failure, say that clearly."));
        followup.add(new LlmMessage("user", "Tool result:\n" + output
                + "\n\nProvide the final answer based only on this result."));
        return safe(adapter.chat(followup, model));
    }

    private ToolContext resolveForcedTool(String userPrompt,
                                          List<TeamAgentRuntime> teamAgents,
                                          Agent manager,
                                          ToolExecutor toolExecutor,
                                          ToolUsageRecorder usageRecorder) {
        String forcedTool = chooseForcedToolForTeam(userPrompt, teamAgents);
        if (forcedTool == null) {
            return null;
        }
        String toolModel = manager != null ? manager.getToolModel() : null;
        String input = buildSearchQuery(userPrompt);
        if (input == null || input.isBlank()) {
            input = normalizeSearchInput(userPrompt, userPrompt);
        }
        log.info("Team tool forced: toolKey={}, input={}", forcedTool, input);
        ToolExecutionResult result = toolExecutor.execute(new ToolExecutionRequest(forcedTool, input, toolModel));
        String output = result != null && result.getOutput() != null ? result.getOutput().trim() : "";
        boolean noResults = output.isBlank() || "No results found.".equalsIgnoreCase(output)
                || output.toLowerCase().contains("blocked") || output.startsWith("Search tool returned");
        List<String> order = List.of("web_search");
        for (String candidate : order) {
            if (!noResults) {
                break;
            }
            if (candidate.equalsIgnoreCase(forcedTool)) {
                continue;
            }
            if (!teamHasTool(teamAgents, candidate)) {
                continue;
            }
            log.info("Team tool forced fallback: toolKey={}, input={}", candidate, input);
            ToolExecutionResult fallback = toolExecutor.execute(new ToolExecutionRequest(candidate, input, toolModel));
            if (fallback != null && fallback.getOutput() != null && !fallback.getOutput().isBlank()) {
                output = fallback.getOutput().trim();
                result = fallback;
                forcedTool = candidate;
                noResults = output.isBlank() || "No results found.".equalsIgnoreCase(output)
                        || output.toLowerCase().contains("blocked") || output.startsWith("Search tool returned");
            }
        }
        log.info("Team tool forced result: toolKey={}, outputSize={}", forcedTool, output.length());
        ToolContext ctx = new ToolContext();
        ctx.tool = forcedTool;
        ctx.output = output;
        ctx.failed = noResults;
        if (!noResults && usageRecorder != null && result != null
                && result.getPromptTokens() != null && result.getCompletionTokens() != null) {
            usageRecorder.record(result.getModel(), result.getPromptTokens(), result.getCompletionTokens());
        }
        if (noResults) {
            ctx.output = "";
        }
        return ctx;
    }

    private String answerWithoutSearch(List<LlmMessage> contextMessages, LlmAdapter adapter, String model) {
        String userPrompt = extractLastUserPrompt(contextMessages);
        if (!shouldAnswerWithoutSearch(userPrompt)) {
            return "抱歉，搜索结果不可用，且该问题依赖最新信息，暂时无法回答。";
        }
        List<LlmMessage> followup = new ArrayList<>();
        followup.addAll(contextMessages);
        followup.add(new LlmMessage("user",
                "Search results are unavailable. Answer the user from general knowledge without citing sources."));
        return safe(adapter.chat(followup, model));
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
                "最新", "新闻", "公告", "通知", "招生", "报名", "录取", "招聘",
                "政策", "官网", "发布", "会议", "直播", "报道", "排名", "榜单",
                "价格", "汇率", "股价", "天气", "时间", "今天", "现在", "刚刚",
                "今年", "本周", "本月", "本季度"
        };
        for (String token : timeSensitive) {
            if (prompt.contains(token)) {
                return false;
            }
        }
        String[] timeSensitiveEn = new String[] {
                "latest", "news", "announcement", "notice", "admission", "recruit",
                "policy", "official", "release", "ranking", "price", "exchange rate",
                "stock", "weather", "today", "now", "current"
        };
        for (String token : timeSensitiveEn) {
            if (lower.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static class ToolContext {
        private String tool;
        private String output;
        private boolean failed;
    }

    private List<LlmMessage> buildPlannerMessages(List<LlmMessage> contextMessages) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system",
                "You are Planner Agent. Build a concise answer plan in bullet points. " +
                        "Focus on correctness, steps, and risks. Do not answer the user directly."));
        messages.addAll(contextMessages);
        return messages;
    }

    private List<LlmMessage> buildResearcherMessages(List<LlmMessage> contextMessages) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system",
                "You are Researcher Agent. Provide key facts, definitions, and examples that support the final answer. " +
                        "Keep it factual and succinct."));
        messages.addAll(contextMessages);
        return messages;
    }

    private List<LlmMessage> buildCriticMessages(List<LlmMessage> contextMessages) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system",
                "You are Critic Agent. Identify errors, missing points, or risky statements. " +
                        "Return a brief critique, not the final answer."));
        messages.addAll(contextMessages);
        return messages;
    }

    private String fallback(String plan, String research, String critique) {
        StringBuilder sb = new StringBuilder();
        if (plan != null && !plan.isBlank()) {
            sb.append(plan);
        }
        if (research != null && !research.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(research);
        }
        if (critique != null && !critique.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Notes:\n").append(critique);
        }
        return sb.toString().trim();
    }
}
