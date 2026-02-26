package com.harmony.backend.ai.tool.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.tool.AgentToolRegistry;
import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolExecutor;
import com.harmony.backend.ai.tool.model.ToolSearchSettings;
import com.harmony.backend.ai.tool.service.ToolSearchSettingsService;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmAdapterRegistry;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.common.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultToolExecutor implements ToolExecutor {

    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final LlmAdapterRegistry adapterRegistry;
    private final ToolSearchSettingsService toolSearchSettingsService;

    @Value("${app.tools.model:deepseek-chat}")
    private String toolModel;

    @Value("${app.tools.search.wikipedia-user-agent:zlAI/1.0 (contact: tomchares0@gmail.com)}")
    private String wikipediaUserAgent;

    @Value("${app.tools.search.wikipedia-proxy-enabled:false}")
    private boolean wikipediaProxyEnabled;

    @Value("${app.tools.search.wikipedia-proxy-url:}")
    private String wikipediaProxyUrl;

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getToolKey())) {
            return ToolExecutionResult.fail("Tool key is required");
        }
        String key = request.getToolKey().trim();
        if (!toolRegistry.isValidKey(key)) {
            return ToolExecutionResult.fail("Unknown tool: " + key);
        }
        String modelOverride = request.getModel();
        switch (key) {
            case "calculator":
                return executeCalculator(request.getInput());
            case "datetime":
                return executeDateTime(request.getInput());
            case "translate":
                return executeTranslation(request.getInput(), modelOverride);
            case "summarize":
                return executeSummarize(request.getInput(), modelOverride);
            case "web_search":
                return executeWebSearch(request.getInput());
            default:
                return ToolExecutionResult.fail("Tool execution not implemented");
        }
    }

    private ToolExecutionResult executeCalculator(String input) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Calculator input is required");
        }
        try {
            BigDecimal value = evaluateExpression(input);
            return ToolExecutionResult.ok(value.stripTrailingZeros().toPlainString());
        } catch (Exception e) {
            return ToolExecutionResult.fail("Invalid expression");
        }
    }

    private ToolExecutionResult executeDateTime(String input) {
        String zone = StringUtils.hasText(input) ? input.trim() : "UTC";
        zone = normalizeTimezone(zone);
        try {
            ZoneId zoneId = ZoneId.of(zone);
            String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ToolExecutionResult.ok(now);
        } catch (Exception e) {
            return ToolExecutionResult.fail("Invalid timezone");
        }
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

    private ToolExecutionResult executeWebSearch(String input) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Search query is required");
        }
        try {
            ToolSearchSettings settings = toolSearchSettingsService.getSettings();
            if (settings != null && StringUtils.hasText(settings.getWikipediaUserAgent())) {
                wikipediaUserAgent = settings.getWikipediaUserAgent();
            }
            if (settings != null && settings.getWikipediaProxyEnabled() != null) {
                wikipediaProxyEnabled = settings.getWikipediaProxyEnabled();
            }
            if (settings != null && StringUtils.hasText(settings.getWikipediaProxyUrl())) {
                wikipediaProxyUrl = settings.getWikipediaProxyUrl();
            }
            if (settings != null) {
                log.info("Web search settings: wiki={}, baike={}, bocha={}, baidu={}, searx={}, serpapi={}, bochaKeyPresent={}, wikiProxy={}",
                        Boolean.TRUE.equals(settings.getWikipediaEnabled()),
                        Boolean.TRUE.equals(settings.getBaikeEnabled()),
                        Boolean.TRUE.equals(settings.getBochaEnabled()),
                        Boolean.TRUE.equals(settings.getBaiduEnabled()),
                        settings.isSearxEnabled(),
                        StringUtils.hasText(settings.getSerpApiKey()),
                        StringUtils.hasText(settings.getBochaApiKey()),
                        Boolean.TRUE.equals(settings.getWikipediaProxyEnabled()));
            } else {
                log.info("Web search settings: null (using defaults)");
            }
            ToolExecutionResult result = executeWebSearchParallel(input, settings);
            if (result != null) {
                return result;
            }
            return ToolExecutionResult.ok("No results found.");
        } catch (Exception e) {
            return ToolExecutionResult.fail("Search request failed");
        }
    }

    private ToolExecutionResult executeWebSearchParallel(String input, ToolSearchSettings settings) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            boolean wikiEnabled = settings == null || settings.getWikipediaEnabled() == null || settings.getWikipediaEnabled();
            boolean baikeEnabled = settings != null && settings.getBaikeEnabled() != null && settings.getBaikeEnabled();
            boolean bochaEnabled = settings != null && settings.getBochaEnabled() != null && settings.getBochaEnabled()
                    && StringUtils.hasText(settings.getBochaApiKey());
            boolean baiduEnabled = settings == null || settings.getBaiduEnabled() == null || settings.getBaiduEnabled();
            boolean apiEnabled = settings != null
                    && ((settings.isSearxEnabled() && StringUtils.hasText(settings.getSearxUrl()))
                    || StringUtils.hasText(settings.getSerpApiKey()));

            java.util.concurrent.ExecutorCompletionService<ToolExecutionResult> ecs =
                    new java.util.concurrent.ExecutorCompletionService<>(executor);
            java.util.List<java.util.concurrent.Future<ToolExecutionResult>> futures = new java.util.ArrayList<>();
            java.util.Map<java.util.concurrent.Future<ToolExecutionResult>, String> sources = new java.util.HashMap<>();

            if (wikiEnabled) {
                log.info("Web search task started: wikipedia");
                java.util.concurrent.Future<ToolExecutionResult> wikiFuture =
                        ecs.submit(() -> executeWikiSearchInternal(input));
                futures.add(wikiFuture);
                sources.put(wikiFuture, "wikipedia");
            } else {
                log.info("Web search task skipped: wikipedia disabled");
            }

            if (baiduEnabled) {
                log.info("Web search task started: baidu");
                java.util.concurrent.Future<ToolExecutionResult> baiduFuture =
                        ecs.submit(() -> executeBaiduSearch(input, settings));
                futures.add(baiduFuture);
                sources.put(baiduFuture, "baidu");
            } else {
                log.info("Web search task skipped: baidu disabled");
            }

            if (bochaEnabled) {
                log.info("Web search task started: bocha");
                java.util.concurrent.Future<ToolExecutionResult> bochaFuture =
                        ecs.submit(() -> executeBochaSearch(input, settings));
                futures.add(bochaFuture);
                sources.put(bochaFuture, "bocha");
            } else {
                log.info("Web search task skipped: bocha disabled");
            }

            if (baikeEnabled) {
                log.info("Web search task started: baike");
                java.util.concurrent.Future<ToolExecutionResult> baikeFuture =
                        ecs.submit(() -> executeBaikeSearch(input));
                futures.add(baikeFuture);
                sources.put(baikeFuture, "baike");
            } else {
                log.info("Web search task skipped: baike disabled");
            }

            if (apiEnabled) {
                log.info("Web search task started: api");
                java.util.concurrent.Future<ToolExecutionResult> apiFuture =
                        ecs.submit(() -> executeSearchViaApi(input, settings));
                futures.add(apiFuture);
                sources.put(apiFuture, "api");
            }

            long deadline = System.currentTimeMillis() + 14000;
            ToolExecutionResult wiki = null;
            ToolExecutionResult baidu = null;
            ToolExecutionResult bocha = null;
            ToolExecutionResult baike = null;
            ToolExecutionResult api = null;

            while (System.currentTimeMillis() < deadline && !futures.isEmpty()) {
                long wait = Math.max(1, deadline - System.currentTimeMillis());
                java.util.concurrent.Future<ToolExecutionResult> done =
                        ecs.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (done == null) {
                    break;
                }
                String source = sources.getOrDefault(done, "unknown");
                ToolExecutionResult result;
                try {
                    result = done.get();
                } catch (Exception ex) {
                    result = null;
                    logSearchCompletion(source, null, ex);
                }

                if (result != null && result.isSuccess() && !isNoResults(result.getOutput())) {
                    log.info("Web search selected result (first-success): {}", source);
                    cancelAll(futures);
                    return withTimeFallbackIfNeeded(input, result);
                }

                if (result != null) {
                    switch (source) {
                        case "wikipedia" -> {
                            if (wiki == null) wiki = result;
                            logSearchCompletion("wikipedia", result, null);
                        }
                        case "baidu" -> {
                            if (baidu == null) baidu = result;
                            logSearchCompletion("baidu", result, null);
                        }
                        case "bocha" -> {
                            if (bocha == null) bocha = result;
                            logSearchCompletion("bocha", result, null);
                        }
                        case "baike" -> {
                            if (baike == null) baike = result;
                            logSearchCompletion("baike", result, null);
                        }
                        case "api" -> {
                            if (api == null) api = result;
                            logSearchCompletion("api", result, null);
                        }
                        default -> logSearchCompletion(source, result, null);
                    }
                }
            }

            // If no usable result arrived in time, fall back to best-effort selection.
            ToolExecutionResult selected = selectBestSearchResult(api, bocha, wiki, baike, baidu);
            if (selected != null) {
                log.info("Web search selected result (fallback): {}", selected == api ? "api"
                        : (selected == bocha ? "bocha"
                        : (selected == wiki ? "wikipedia" : (selected == baike ? "baike" : "baidu"))));
                return withTimeFallbackIfNeeded(input, selected);
            }
            if (isTimeLikeQuery(input)) {
                return fetchTimeFallback(input);
            }
            if (!futures.isEmpty()) {
                List<String> pending = new java.util.ArrayList<>();
                for (java.util.concurrent.Future<ToolExecutionResult> f : futures) {
                    if (!f.isDone()) {
                        pending.add(sources.getOrDefault(f, "unknown"));
                    }
                }
                log.warn("Web search timed out: pendingTasks={} sources={}", futures.size(), pending);
            }
            return ToolExecutionResult.ok("No results found.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private void cancelAll(java.util.List<java.util.concurrent.Future<ToolExecutionResult>> futures) {
        for (java.util.concurrent.Future<ToolExecutionResult> f : futures) {
            try {
                if (!f.isDone()) {
                    log.info("Web search cancel pending task");
                }
                f.cancel(true);
            } catch (Exception ignored) {
            }
        }
    }

    private String detectSource(ToolExecutionResult result,
                                ToolExecutionResult wiki,
                                ToolExecutionResult baidu,
                                ToolExecutionResult bocha,
                                ToolExecutionResult baike,
                                ToolExecutionResult api) {
        if (result == wiki) {
            return "wikipedia";
        }
        if (result == baidu) {
            return "baidu";
        }
        if (result == bocha) {
            return "bocha";
        }
        if (result == baike) {
            return "baike";
        }
        if (result == api) {
            return "api";
        }
        return "unknown";
    }

    private void logSearchCompletion(String source, ToolExecutionResult result, Throwable ex) {
        if (ex != null) {
            log.warn("Web search task failed: {} error={}", source, ex.getMessage());
            return;
        }
        if (result == null) {
            log.info("Web search task finished: {} result=null", source);
            return;
        }
        log.info("Web search task finished: {} success={} outputSize={}", source,
                result.isSuccess(), result.getOutput() == null ? 0 : result.getOutput().length());
    }

    private ToolExecutionResult selectBestSearchResult(ToolExecutionResult api,
                                                       ToolExecutionResult bocha,
                                                       ToolExecutionResult wiki,
                                                       ToolExecutionResult baike,
                                                       ToolExecutionResult baidu) {
        if (isUsableSearchResult(api)) {
            return api;
        }
        if (isUsableSearchResult(bocha)) {
            return bocha;
        }
        if (isUsableSearchResult(wiki)) {
            return wiki;
        }
        if (isUsableSearchResult(baike)) {
            return baike;
        }
        if (isUsableSearchResult(baidu)) {
            return baidu;
        }
        return null;
    }

    private boolean isUsableSearchResult(ToolExecutionResult result) {
        return result != null && result.isSuccess() && !isNoResults(result.getOutput());
    }

    private ToolExecutionResult withTimeFallbackIfNeeded(String input, ToolExecutionResult result) {
        if (result == null) {
            return null;
        }
        if (!isTimeLikeQuery(input)) {
            return result;
        }
        String output = result.getOutput();
        if (containsTimeValue(output)) {
            return result;
        }
        ToolExecutionResult fallback = fetchTimeFallback(input);
        if (fallback == null || !fallback.isSuccess() || isNoResults(fallback.getOutput())) {
            return result;
        }
        String merged = output + "\n\nAdditional time source:\n" + fallback.getOutput();
        return ToolExecutionResult.ok(merged);
    }

    private ToolExecutionResult executeSearchViaApi(String input, ToolSearchSettings settings) {
        if (settings != null && settings.isSearxEnabled() && StringUtils.hasText(settings.getSearxUrl())) {
            return executeSearxSearch(input, settings);
        }
        if (settings != null && StringUtils.hasText(settings.getSerpApiKey())) {
            return executeSerpApiSearch(input, settings);
        }
        return null;
    }

    private ToolExecutionResult executeSearxSearch(String input, ToolSearchSettings settings) {
        try {
            List<String> queries = expandQueries(input);
            for (String query : queries) {
                String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                String base = settings.getSearxUrl();
                if (!StringUtils.hasText(base)) {
                    return ToolExecutionResult.ok("No results found.");
                }
                base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
                String url = base + "/search?q=" + encoded + "&format=json&language=zh-CN";
                HttpResponse<String> response = httpGet(url);
                if (response.statusCode() >= 400) {
                    continue;
                }
                List<String> results = parseSearxResults(response.body(), 5);
                if (results.isEmpty()) {
                    continue;
                }
                return ToolExecutionResult.ok(formatApiResults(results));
            }
            return ToolExecutionResult.ok("No results found.");
        } catch (Exception e) {
            return ToolExecutionResult.fail("Searx search failed");
        }
    }

    private ToolExecutionResult executeSerpApiSearch(String input, ToolSearchSettings settings) {
        try {
            List<String> queries = expandQueries(input);
            for (String query : queries) {
                String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                String engine = StringUtils.hasText(settings.getSerpApiEngine()) ? settings.getSerpApiEngine().trim() : "baidu";
                String url = "https://serpapi.com/search.json?engine=" + engine
                        + "&q=" + encoded + "&api_key=" + URLEncoder.encode(settings.getSerpApiKey(), StandardCharsets.UTF_8);
                HttpResponse<String> response = httpGet(url);
                if (response.statusCode() >= 400) {
                    continue;
                }
                List<String> results = parseSerpApiResults(response.body(), 5);
                if (results.isEmpty()) {
                    continue;
                }
                return ToolExecutionResult.ok(formatApiResults(results));
            }
            return ToolExecutionResult.ok("No results found.");
        } catch (Exception e) {
            return ToolExecutionResult.fail("SerpAPI search failed");
        }
    }

    private boolean isNoResults(String output) {
        if (!StringUtils.hasText(output)) {
            return true;
        }
        String trimmed = output.trim();
        return trimmed.isEmpty() || "No results found.".equalsIgnoreCase(trimmed)
                || trimmed.startsWith("Search tool returned");
    }

    private boolean containsTimeValue(String output) {
        if (!StringUtils.hasText(output)) {
            return false;
        }
        String text = output.replaceAll("\\s+", " ");
        if (text.matches(".*\\b\\d{4}-\\d{2}-\\d{2}\\b.*")) {
            return true;
        }
        if (text.matches(".*\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b.*")) {
            return true;
        }
        return false;
    }

    private ToolExecutionResult executeWikiSearchInternal(String input) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Search query is required");
        }
        try {
            List<String> queries = expandQueries(input);
            for (String query : queries) {
                boolean useChinese = containsCjk(query);
                List<String> hosts = new ArrayList<>();
                if (useChinese) {
                    hosts.add("zh.wikipedia.org");
                    hosts.add("en.wikipedia.org");
                } else {
                    hosts.add("en.wikipedia.org");
                    hosts.add("zh.wikipedia.org");
                }
                for (String host : hosts) {
                    try {
                        List<String> results = executeWikipediaSearch(host, query);
                        if (!results.isEmpty()) {
                            return ToolExecutionResult.ok(fetchAndFormatSearchResults(results, 3));
                        }
                    } catch (Exception ex) {
                        log.warn("Wikipedia search failed: host={}, query='{}', error={}", host, query, ex.getMessage());
                    }
                }
            }
            return ToolExecutionResult.ok("No results found.");
        } catch (Exception e) {
            log.warn("Wikipedia search error: {}", e.getMessage());
            return ToolExecutionResult.fail("Wikipedia search failed");
        }
    }

    private List<String> executeWikipediaSearch(String host, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://" + host
                + "/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=5&srsearch=" + encoded;
        HttpResponse<String> response;
        try {
            response = httpGet(url, wikipediaUserAgent);
        } catch (Exception ex) {
            return tryWikipediaProxy(host, url, encoded);
        }
        if (response.statusCode() >= 400) {
            return tryWikipediaProxy(host, url, encoded);
        }
        List<String> results = parseWikipediaResults(response.body(), host, 5);
        log.info("Wikipedia search: host={}, query='{}' results={}", host, query, results.size());
        if (!results.isEmpty()) {
            return results;
        }
        String openUrl = "https://" + host
                + "/w/api.php?action=opensearch&format=json&utf8=1&limit=5&search=" + encoded;
        HttpResponse<String> openResponse;
        try {
            openResponse = httpGet(openUrl, wikipediaUserAgent);
        } catch (Exception ex) {
            return tryWikipediaProxy(host, openUrl, encoded);
        }
        if (openResponse.statusCode() >= 400) {
            return tryWikipediaProxy(host, openUrl, encoded);
        }
        List<String> openResults = parseWikipediaOpenSearch(openResponse.body(), host, 5);
        log.info("Wikipedia opensearch: host={}, query='{}' results={}", host, query, openResults.size());
        return openResults;
    }

    private List<String> tryWikipediaProxy(String host, String url, String encodedQuery) throws Exception {
        if (!wikipediaProxyEnabled || wikipediaProxyUrl == null || wikipediaProxyUrl.isBlank()) {
            return List.of();
        }
        String base = wikipediaProxyUrl.endsWith("/") ? wikipediaProxyUrl : wikipediaProxyUrl + "/";
        String proxyUrl = base + url;
        log.info("Wikipedia proxy request: host={}, url={}", host, proxyUrl);
        HttpResponse<String> response = httpGet(proxyUrl, wikipediaUserAgent);
        if (response.statusCode() >= 400) {
            return List.of();
        }
        List<String> results = parseWikipediaResults(response.body(), host, 5);
        if (!results.isEmpty()) {
            return results;
        }
        String openUrl = "https://" + host
                + "/w/api.php?action=opensearch&format=json&utf8=1&limit=5&search=" + encodedQuery;
        String proxyOpenUrl = base + openUrl;
        log.info("Wikipedia proxy opensearch: host={}, url={}", host, proxyOpenUrl);
        HttpResponse<String> openResponse = httpGet(proxyOpenUrl, wikipediaUserAgent);
        if (openResponse.statusCode() >= 400) {
            return List.of();
        }
        return parseWikipediaOpenSearch(openResponse.body(), host, 5);
    }

    private ToolExecutionResult executeBaiduSearch(String input, ToolSearchSettings settings) {
        if (settings != null && Boolean.FALSE.equals(settings.getBaiduEnabled())) {
            log.info("Baidu search skipped by setting: disabled");
            return ToolExecutionResult.ok("No results found.");
        }
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Search query is required");
        }
        try {
            if (settings != null && StringUtils.hasText(settings.getSerpApiKey())
                    && "baidu".equalsIgnoreCase(settings.getSerpApiEngine())) {
                return executeSerpApiSearch(input, settings);
            }
            List<String> queries = expandQueries(input);
            for (String query : queries) {
                List<String> results = executeBaiduQuery(query);
                if (results.isEmpty()) {
                    continue;
                }
                return ToolExecutionResult.ok(fetchAndFormatSearchResults(results, 3));
            }
            log.info("Baidu search finished with no results: query='{}'", input);
            return ToolExecutionResult.ok("No results found.");
        } catch (Exception e) {
            return ToolExecutionResult.fail("Baidu search failed");
        }
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
                var map = objectMapper.readValue(trimmed, new TypeReference<java.util.Map<String, Object>>() {});
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
                .replace("\u53CA", ",")
                .replace("\u548C", ",")
                .replace("\u4E0E", ",")
                .replace(" and ", ",")
                .replace(" AND ", ",")
                .replace("and", ",")
                .replace("AND", ",")
                .replaceAll("[\\u3001\\uFF0C\\u3002\\uFF1B\\uFF1A]", ",");
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

    private List<String> parseWikipediaResults(String json, String host, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return results;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Map<String, Object> query = (Map<String, Object>) root.get("query");
            if (query == null) {
                return results;
            }
            List<Map<String, Object>> search = (List<Map<String, Object>>) query.get("search");
            if (search == null) {
                return results;
            }
            for (Map<String, Object> item : search) {
                if (results.size() >= limit) {
                    break;
                }
                String title = item.get("title") != null ? item.get("title").toString() : "";
                String snippet = item.get("snippet") != null ? item.get("snippet").toString() : "";
                String cleanSnippet = snippet.replaceAll("<.*?>", "").replaceAll("\\s+", " ").trim();
                String linkTitle = title.replace(" ", "_");
                String url = "https://" + host + "/wiki/" + URLEncoder.encode(linkTitle, StandardCharsets.UTF_8);
                if (!title.isBlank()) {
                    results.add(title + " - " + url + (cleanSnippet.isBlank() ? "" : " (" + cleanSnippet + ")"));
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<String> parseWikipediaOpenSearch(String json, String host, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return results;
        }
        try {
            List<Object> root = objectMapper.readValue(json, new TypeReference<List<Object>>() {});
            if (root.size() < 4) {
                return results;
            }
            List<Object> titles = (List<Object>) root.get(1);
            List<Object> descriptions = (List<Object>) root.get(2);
            List<Object> links = (List<Object>) root.get(3);
            for (int i = 0; i < titles.size() && results.size() < limit; i++) {
                String title = titles.get(i) != null ? titles.get(i).toString() : "";
                String url = links.size() > i && links.get(i) != null ? links.get(i).toString() : "";
                String snippet = descriptions.size() > i && descriptions.get(i) != null ? descriptions.get(i).toString() : "";
                if (!title.isBlank()) {
                    String resolvedUrl = !url.isBlank()
                            ? url
                            : "https://" + host + "/wiki/" + URLEncoder.encode(title.replace(" ", "_"), StandardCharsets.UTF_8);
                    results.add(title + " - " + resolvedUrl + (snippet.isBlank() ? "" : " (" + snippet + ")"));
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<String> parseBaiduResults(String html, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(html)) {
            return results;
        }
        if (isBaiduBlocked(html)) {
            return results;
        }
        Pattern pattern = Pattern.compile(
                "<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && results.size() < limit) {
            String link = matcher.group(1);
            String title = matcher.group(2).replaceAll("<.*?>", "").replaceAll("\\s+", " ").trim();
            if (!title.isEmpty()) {
                results.add(title + " - " + link);
            }
        }
        return results;
    }

    private List<String> parseSearxResults(String json, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return results;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("results");
            if (items == null) {
                return results;
            }
            for (Map<String, Object> item : items) {
                if (results.size() >= limit) {
                    break;
                }
                String title = item.get("title") != null ? item.get("title").toString() : "";
                String url = item.get("url") != null ? item.get("url").toString() : "";
                String content = item.get("content") != null ? item.get("content").toString() : "";
                String clean = content.replaceAll("<.*?>", "").replaceAll("\\s+", " ").trim();
                if (!title.isBlank()) {
                    results.add(title + " - " + url + (clean.isBlank() ? "" : " (" + clean + ")"));
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<String> parseSerpApiResults(String json, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return results;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> items = (List<Map<String, Object>>) root.get("organic_results");
            if (items == null) {
                return results;
            }
            for (Map<String, Object> item : items) {
                if (results.size() >= limit) {
                    break;
                }
                String title = item.get("title") != null ? item.get("title").toString() : "";
                String url = item.get("link") != null ? item.get("link").toString() : "";
                String snippet = item.get("snippet") != null ? item.get("snippet").toString() : "";
                if (!title.isBlank()) {
                    results.add(title + " - " + url + (snippet.isBlank() ? "" : " (" + snippet + ")"));
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<String> parseBochaResults(String json, int limit) {
        List<String> results = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return results;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object code = root.get("code");
            if (code != null && !"200".equals(code.toString()) && !"0".equals(code.toString())) {
                return results;
            }
            Map<String, Object> data = (Map<String, Object>) root.get("data");
            if (data == null) {
                return results;
            }
            Map<String, Object> webPages = (Map<String, Object>) data.get("webPages");
            if (webPages == null) {
                return results;
            }
            List<Map<String, Object>> values = (List<Map<String, Object>>) webPages.get("value");
            if (values == null) {
                return results;
            }
            for (Map<String, Object> item : values) {
                if (results.size() >= limit) {
                    break;
                }
                String title = item.get("name") != null ? item.get("name").toString() : "";
                String url = item.get("url") != null ? item.get("url").toString() : "";
                String summary = item.get("summary") != null ? item.get("summary").toString() : "";
                if (summary.isBlank()) {
                    summary = item.get("snippet") != null ? item.get("snippet").toString() : "";
                }
                String site = item.get("siteName") != null ? item.get("siteName").toString() : "";
                String date = item.get("datePublished") != null ? item.get("datePublished").toString() : "";
                if (date.isBlank()) {
                    date = item.get("dateLastCrawled") != null ? item.get("dateLastCrawled").toString() : "";
                }
                StringBuilder line = new StringBuilder();
                line.append(title.isBlank() ? "Result" : title);
                if (!url.isBlank()) {
                    line.append(" - ").append(url);
                }
                if (!site.isBlank()) {
                    line.append(" (").append(site).append(")");
                }
                if (!date.isBlank()) {
                    line.append(" ").append(date);
                }
                if (!summary.isBlank()) {
                    line.append(" (").append(summary.replaceAll("\\s+", " ").trim()).append(")");
                }
                results.add(line.toString());
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private boolean isBochaSuccess(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            Object code = root.get("code");
            if (code == null) {
                return true;
            }
            String value = code.toString();
            return "200".equals(value) || "0".equals(value);
        } catch (Exception e) {
            return false;
        }
    }

    private ToolExecutionResult executeBaikeSearch(String input) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.ok("No results found.");
        }
        try {
            List<String> queries = expandQueries(input);
            for (String query : queries) {
                String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                String url = "https://baike.baidu.com/search/word?word=" + encoded;
                HttpResponse<String> response = httpGet(url, desktopUserAgent(), 8);
                if (response.statusCode() >= 400) {
                    continue;
                }
                String html = response.body();
                if (!StringUtils.hasText(html)) {
                    continue;
                }
                String title = matchFirst(html, "<h1[^>]*>(.*?)</h1>");
                String summary = matchFirst(html, "<div[^>]*class=\"summary\"[^>]*>(.*?)</div>");
                if (!StringUtils.hasText(summary)) {
                    summary = matchFirst(html, "<div[^>]*class=\"lemma-summary\"[^>]*>(.*?)</div>");
                }
                String cleanedTitle = cleanHtml(title);
                String cleanedSummary = cleanHtml(summary);
                if (StringUtils.hasText(cleanedTitle) || StringUtils.hasText(cleanedSummary)) {
                    StringBuilder sb = new StringBuilder();
                    if (StringUtils.hasText(cleanedTitle)) {
                        sb.append("Title: ").append(cleanedTitle).append("\n");
                    }
                    sb.append("Source: ").append(url).append("\n");
                    if (StringUtils.hasText(cleanedSummary)) {
                        sb.append("Summary: ").append(cleanedSummary);
                    }
                    return ToolExecutionResult.ok(sb.toString().trim());
                }
            }
        } catch (Exception ignored) {
        }
        return ToolExecutionResult.ok("No results found.");
    }

    private ToolExecutionResult executeBochaSearch(String input, ToolSearchSettings settings) {
        if (settings == null || !StringUtils.hasText(settings.getBochaApiKey())) {
            return ToolExecutionResult.ok("No results found.");
        }
        String endpoint = StringUtils.hasText(settings.getBochaEndpoint())
                ? settings.getBochaEndpoint()
                : "https://api.bocha.cn/v1/web-search";
        try {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("query", input.trim());
            payload.put("summary", true);
            payload.put("freshness", "noLimit");
            payload.put("count", 5);
            String json = objectMapper.writeValueAsString(payload);
            log.info("Bocha search request: endpoint='{}' query='{}'", endpoint, input.trim());
            HttpResponse<String> response = httpPostJson(endpoint, json, settings.getBochaApiKey());
            if (response.statusCode() >= 400) {
                log.warn("Bocha search http error: status={}", response.statusCode());
                return ToolExecutionResult.ok("No results found.");
            }
            if (!isBochaSuccess(response.body())) {
                log.warn("Bocha search response not success.");
                return ToolExecutionResult.ok("No results found.");
            }
            List<String> results = parseBochaResults(response.body(), 5);
            if (results.isEmpty()) {
                log.info("Bocha search results empty.");
                return ToolExecutionResult.ok("No results found.");
            }
            log.info("Bocha search results: count={}", results.size());
            return ToolExecutionResult.ok(formatApiResults(results));
        } catch (Exception e) {
            return ToolExecutionResult.fail("Bocha search failed");
        }
    }

    private String matchFirst(String html, String regex) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private String cleanHtml(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        return input.replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> executeBaiduQuery(String query) throws Exception {
        log.info("Baidu search request: query='{}'", query);
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String url = "https://www.baidu.com/s?wd=" + encoded;
        HttpResponse<String> response = httpGet(url, desktopUserAgent(), 6);
        if (response.statusCode() >= 400) {
            return List.of();
        }
        boolean blocked = isBaiduBlocked(response.body());
        List<String> results = parseBaiduResults(response.body(), 5);
        log.info("Baidu search desktop: query='{}' blocked={} results={}", query, blocked, results.size());
        if (results.isEmpty() || blocked) {
            String mobileUrl = "https://m.baidu.com/s?wd=" + encoded;
            HttpResponse<String> mobileResponse = httpGet(mobileUrl, mobileUserAgent(), 6);
            if (mobileResponse.statusCode() < 400) {
                boolean mobileBlocked = isBaiduBlocked(mobileResponse.body());
                results = parseBaiduResults(mobileResponse.body(), 5);
                log.info("Baidu search mobile: query='{}' blocked={} results={}", query, mobileBlocked, results.size());
            }
        }
        return results == null ? List.of() : results;
    }


    private HttpResponse<String> httpGet(String url) throws Exception {
        return httpGet(url, desktopUserAgent());
    }

    private HttpResponse<String> httpPostJson(String url, String json, String apiKey) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", desktopUserAgent())
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpGet(String url, String userAgent) throws Exception {
        return httpGet(url, userAgent, 12);
    }

    private HttpResponse<String> httpGet(String url, String userAgent, int timeoutSeconds) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String formatResults(List<String> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatApiResults(List<String> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    private String fetchAndFormatSearchResults(List<String> results, int maxFetch) {
        StringBuilder sb = new StringBuilder();
        int fetchCount = Math.min(maxFetch, results.size());
        for (int i = 0; i < results.size(); i++) {
            String row = results.get(i);
            String[] parts = row.split(" - ", 2);
            String title = parts.length > 0 ? parts[0].trim() : "";
            String url = parts.length > 1 ? parts[1].trim() : "";
            sb.append(i + 1).append(". ").append(title);
            if (!url.isBlank()) {
                sb.append("\nSource: ").append(url);
            }
            if (i < fetchCount && !url.isBlank()) {
                String text = fetchPageText(url, 1200);
                if (StringUtils.hasText(text)) {
                    sb.append("\nSnippet: ").append(text);
                }
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private String fetchPageText(String url, int maxChars) {
        try {
            HttpResponse<String> response = httpGet(url, desktopUserAgent());
            if (response.statusCode() >= 400) {
                return "";
            }
            String html = response.body();
            if (!StringUtils.hasText(html)) {
                return "";
            }
            String text = html
                    .replaceAll("(?is)<script.*?>.*?</script>", " ")
                    .replaceAll("(?is)<style.*?>.*?</style>", " ")
                    .replaceAll("(?is)<nav.*?>.*?</nav>", " ")
                    .replaceAll("(?is)<header.*?>.*?</header>", " ")
                    .replaceAll("(?is)<footer.*?>.*?</footer>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!StringUtils.hasText(text)) {
                return "";
            }
            String cleaned = extractReadableText(text, maxChars);
            return cleaned;
        } catch (Exception e) {
            return "";
        }
    }

        private String extractReadableText(String text, int maxChars) {
        String[] parts = text.split("[\\.\\u3002\\uFF01\\uFF1F]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String s = part.trim();
            if (s.length() < 30) {
                continue;
            }
            if (s.toLowerCase().contains("cookie") || s.toLowerCase().contains("privacy")
                    || s.toLowerCase().contains("subscribe")) {
                continue;
            }
            sb.append(s).append(". ");
            if (sb.length() >= maxChars) {
                break;
            }
        }
        String out = sb.toString().trim();
        if (out.length() > maxChars) {
            out = out.substring(0, maxChars) + "...";
        }
        return out;
    }

    private List<String> expandQueries(String input) {
        List<String> queries = new ArrayList<>();
        if (!StringUtils.hasText(input)) {
            return queries;
        }
        String q = input.trim();
        queries.add(q);
        String simplified = q.replaceAll("[()鑼傚綍鑱㈣寕褰曡仯,鑼傚綍鑱﹁尗鑱欒仠鑼傚綍鑱?鑼傚綍?鑼仚?\\\\|]", " ").replaceAll("\\s+", " ").trim();
        if (!simplified.equals(q)) {
            queries.add(simplified);
        }
        String cleaned = simplified.replaceAll("\\d+", " ").replaceAll("\\s+", " ").trim();
        String[] parts = cleaned.split(" ");
        if (parts.length > 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length && i < 6; i++) {
                if (parts[i].isBlank()) {
                    continue;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(parts[i]);
            }
            if (sb.length() > 0) {
                queries.add(sb.toString());
            }
        }
        StringBuilder core = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z0-9\\-]{3,}|[\\u4E00-\\u9FFF]{2,}").matcher(cleaned);
        int count = 0;
        while (m.find() && count < 6) {
            String token = m.group();
            if (core.length() > 0) core.append(' ');
            core.append(token);
            count++;
        }
        if (core.length() > 0) {
            queries.add(core.toString());
        }
        List<String> unique = new ArrayList<>();
        for (String item : queries) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!unique.contains(trimmed)) {
                unique.add(trimmed);
            }
        }
        return unique;
    }

        private boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private boolean isTimeLikeQuery(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("time")
                || lower.contains("now")
                || lower.contains("beijing")
                || lower.contains("shanghai")
                || lower.contains("china")
                || text.contains("\u5317\u4eac\u65f6\u95f4")
                || text.contains("\u4e0a\u6d77")
                || text.contains("\u4e2d\u56fd")
                || text.contains("\u65f6\u95f4")
                || text.contains("\u51e0\u70b9");
    }

    private ToolExecutionResult fetchTimeFallback(String input) {
        String zone = "Asia/Shanghai";
        if (input != null) {
            String lower = input.toLowerCase();
            if (lower.contains("utc") || lower.contains("gmt")) {
                zone = "UTC";
            } else if (lower.contains("beijing") || lower.contains("shanghai") || lower.contains("china")
                    || input.contains("\u5317\u4eac") || input.contains("\u4e0a\u6d77") || input.contains("\u4e2d\u56fd")) {
                zone = "Asia/Shanghai";
            }
        }
        try {
            String api = "https://worldtimeapi.org/api/timezone/" + URLEncoder.encode(zone, StandardCharsets.UTF_8);
            HttpResponse<String> response = httpGet(api, desktopUserAgent());
            if (response.statusCode() >= 400) {
                return ToolExecutionResult.ok("No results found.");
            }
            Map<String, Object> root = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            Object datetime = root.get("datetime");
            if (datetime != null) {
                String output = "Source: https://worldtimeapi.org/api/timezone/" + zone + "\n"
                        + "Time: " + datetime.toString();
                return ToolExecutionResult.ok(output);
            }
        } catch (Exception ignored) {
        }
        return ToolExecutionResult.ok("No results found.");
    }

    private boolean isBaiduBlocked(String html) {
        if (!StringUtils.hasText(html)) {
            return false;
        }
        String lower = html.toLowerCase();
        return lower.contains("\u767e\u5ea6\u5b89\u5168\u9a8c\u8bc1")
                || lower.contains("\u5b89\u5168\u9a8c\u8bc1")
                || lower.contains("\u8bbf\u95ee\u5f02\u5e38")
                || lower.contains("\u8bf7\u8f93\u5165\u9a8c\u8bc1\u7801")
                || lower.contains("antispider")
                || lower.contains("verify");
    }

    private String desktopUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
    }

    private String mobileUserAgent() {
        return "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";
    }

    private String normalizeTimezone(String zone) {
        if (!StringUtils.hasText(zone)) {
            return "UTC";
        }
        String trimmed = zone.trim();
        String lower = trimmed.toLowerCase();
        if (lower.contains("shanghai") || lower.contains("beijing") || lower.contains("china")
                || trimmed.contains("鐩茶祩鑱ゅ繖纰岃矾") || trimmed.contains("姘撹仸鑱寸洸娼炲崲") || trimmed.contains("鐩茶祩棰呮皳鑱搁檰")) {
            return "Asia/Shanghai";
        }
        if (lower.startsWith("utc") || lower.startsWith("gmt")) {
            String offset = trimmed.replace("UTC", "").replace("utc", "")
                    .replace("GMT", "").replace("gmt", "").trim();
            if (offset.startsWith("+") || offset.startsWith("-")) {
                return "UTC" + offset;
            }
        }
        return trimmed;
    }

    private static class TranslationInput {
        private final String text;
        private final String target;

        private TranslationInput(String text, String target) {
            this.text = text;
            this.target = target;
        }
    }

    private BigDecimal evaluateExpression(String expr) {
        List<String> tokens = tokenize(expr);
        List<String> rpn = toRpn(tokens);
        return evalRpn(rpn);
    }

    private List<String> tokenize(String expr) {
        List<String> tokens = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                number.append(c);
                continue;
            }
            if (number.length() > 0) {
                tokens.add(number.toString());
                number.setLength(0);
            }
            if (isOperator(c) || c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
            }
        }
        if (number.length() > 0) {
            tokens.add(number.toString());
        }
        return tokens;
    }

    private List<String> toRpn(List<String> tokens) {
        List<String> output = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String token : tokens) {
            if (isNumber(token)) {
                output.add(token);
                continue;
            }
            if (isOperator(token)) {
                while (!stack.isEmpty() && isOperator(stack.peek())
                        && precedence(stack.peek()) >= precedence(token)) {
                    output.add(stack.pop());
                }
                stack.push(token);
                continue;
            }
            if ("(".equals(token)) {
                stack.push(token);
                continue;
            }
            if (")".equals(token)) {
                while (!stack.isEmpty() && !"(".equals(stack.peek())) {
                    output.add(stack.pop());
                }
                if (!stack.isEmpty() && "(".equals(stack.peek())) {
                    stack.pop();
                }
            }
        }
        while (!stack.isEmpty()) {
            output.add(stack.pop());
        }
        return output;
    }

    private BigDecimal evalRpn(List<String> rpn) {
        Deque<BigDecimal> stack = new ArrayDeque<>();
        for (String token : rpn) {
            if (isNumber(token)) {
                stack.push(new BigDecimal(token));
                continue;
            }
            BigDecimal b = stack.pop();
            BigDecimal a = stack.pop();
            switch (token) {
                case "+":
                    stack.push(a.add(b));
                    break;
                case "-":
                    stack.push(a.subtract(b));
                    break;
                case "*":
                    stack.push(a.multiply(b));
                    break;
                case "/":
                    stack.push(a.divide(b, MathContext.DECIMAL64));
                    break;
                default:
                    throw new IllegalArgumentException("Invalid operator");
            }
        }
        return stack.pop();
    }

    private boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private boolean isOperator(String token) {
        return "+".equals(token) || "-".equals(token) || "*".equals(token) || "/".equals(token);
    }

    private int precedence(String op) {
        return ("*".equals(op) || "/".equals(op)) ? 2 : 1;
    }

    private boolean isNumber(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }
}

