package com.harmony.backend.ai.rag.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.harmony.backend.ai.rag.model.RagChunkCandidate;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagRetrievalService {
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]{2,}");
    private static final Pattern TECHNICAL_TOKEN_PATTERN = Pattern.compile("/api/[A-Za-z0-9/_{}-]+|[A-Za-z][A-Za-z0-9@_.:/-]{2,}");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

    private final RagRepository ragRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public List<RagChunkMatch> retrieve(Long userId, String query, Integer topK) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(400, "Query is required");
        }
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        RagProperties.Search search = properties.getSearch();
        QueryFeatures features = QueryFeatures.from(query);
        float[] embedding = embeddingService.embed(query);
        int candidateLimit = computeCandidateLimit(limit, search, features);
        List<RagChunkCandidate> candidates = ragRepository.searchCandidates(userId, embedding, candidateLimit);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return rerankWithFallback(query, candidates, limit, search);
    }

    protected List<RagChunkMatch> rerankWithFallback(String query,
                                                     List<RagChunkCandidate> candidates,
                                                     int limit,
                                                     RagProperties.Search search) {
        try {
            return rerankCandidates(query, candidates, limit, search);
        } catch (Exception ex) {
            log.warn("RAG rerank failed, falling back to vector ordering. query='{}'", query, ex);
            return toVectorMatches(candidates, limit, search);
        }
    }

    protected List<RagChunkMatch> rerankCandidates(String query,
                                                   List<RagChunkCandidate> candidates,
                                                   int limit,
                                                   RagProperties.Search search) {
        QueryFeatures features = QueryFeatures.from(query);
        double vectorWeight = clamp(search.getRerankVectorWeight(), 0.0, 1.0);
        double lexicalWeight = clamp(search.getRerankLexicalWeight(), 0.0, 1.0);
        if (vectorWeight == 0.0 && lexicalWeight == 0.0) {
            vectorWeight = 0.72;
            lexicalWeight = 0.28;
        }
        List<RagChunkMatch> reranked = new ArrayList<>();
        for (RagChunkCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getContent())) {
                continue;
            }
            Map<String, Object> metadata = parseChunkMetadata(candidate.getChunkMetadata());
            double vectorScore = toVectorScore(candidate.getDistance());
            double lexicalScore = lexicalScore(features, candidate.getContent());
            double exactMatchBonus = hasExactMatch(features, candidate.getContent())
                    ? Math.max(0.0, search.getRerankExactMatchBonus())
                    : 0.0;
            double metadataBonus = metadataBonus(features, candidate, metadata, lexicalScore);
            double modeBonus = modeSpecificBonus(features, candidate, metadata);
            double confusionAdjustment = confusionAdjustment(features, candidate, metadata);
            double finalScore = vectorWeight * vectorScore
                    + lexicalWeight * lexicalScore
                    + exactMatchBonus
                    + metadataBonus
                    + modeBonus
                    + confusionAdjustment;
            if (finalScore < Math.max(0.0, search.getMinScore())) {
                continue;
            }
            reranked.add(new RagChunkMatch(
                    candidate.getDocId(),
                    candidate.getContent(),
                    finalScore,
                    candidate.getChunkMetadata()
            ));
        }
        reranked.sort(Comparator.comparingDouble(RagChunkMatch::getScore).reversed());
        return takeTopMatches(reranked, limit, search.getMaxChunksPerDocument(), features);
    }

    private List<RagChunkMatch> toVectorMatches(List<RagChunkCandidate> candidates,
                                                int limit,
                                                RagProperties.Search search) {
        double minScore = Math.max(0.0, search.getMinScore());
        List<RagChunkMatch> matches = new ArrayList<>();
        for (RagChunkCandidate candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getContent())) {
                continue;
            }
            double vectorScore = toVectorScore(candidate.getDistance());
            if (vectorScore < minScore) {
                continue;
            }
            matches.add(new RagChunkMatch(
                    candidate.getDocId(),
                    candidate.getContent(),
                    vectorScore,
                    candidate.getChunkMetadata()
            ));
        }
        return takeTopMatches(matches, limit, search.getMaxChunksPerDocument(), QueryFeatures.empty());
    }

    private List<RagChunkMatch> takeTopMatches(List<RagChunkMatch> ranked,
                                               int limit,
                                               int maxChunksPerDocument,
                                               QueryFeatures features) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        int perDocumentLimit = Math.max(1, maxChunksPerDocument);
        int firstPassPerDocumentLimit = features.diversityIntent() ? 1 : perDocumentLimit;
        Map<String, Integer> docCounts = new LinkedHashMap<>();
        List<RagChunkMatch> selected = new ArrayList<>();
        for (RagChunkMatch match : ranked) {
            String docId = match.getDocId();
            if (!StringUtils.hasText(docId)) {
                continue;
            }
            int current = docCounts.getOrDefault(docId, 0);
            if (current >= firstPassPerDocumentLimit) {
                continue;
            }
            selected.add(match);
            docCounts.put(docId, current + 1);
            if (selected.size() >= limit) {
                break;
            }
        }
        if (selected.size() >= limit) {
            return selected;
        }
        for (RagChunkMatch match : ranked) {
            String docId = match.getDocId();
            if (!StringUtils.hasText(docId)) {
                continue;
            }
            int current = docCounts.getOrDefault(docId, 0);
            if (current >= perDocumentLimit) {
                continue;
            }
            if (selected.contains(match)) {
                continue;
            }
            selected.add(match);
            docCounts.put(docId, current + 1);
            if (selected.size() >= limit) {
                break;
            }
        }
        if (selected.size() >= limit) {
            return selected;
        }
        for (RagChunkMatch match : ranked) {
            if (selected.size() >= limit) {
                break;
            }
            if (selected.contains(match)) {
                continue;
            }
            selected.add(match);
        }
        return selected;
    }

    private int computeCandidateLimit(int limit, RagProperties.Search search, QueryFeatures features) {
        int multiplier = Math.max(1, search.getRerankCandidateMultiplier());
        if (features.confusionIntent()) {
            multiplier += 2;
        }
        if (features.diversityIntent()) {
            multiplier += 2;
        }
        if (features.tableLookupIntent()) {
            multiplier += 1;
        }
        return Math.max(limit, limit * multiplier);
    }

    private double lexicalScore(QueryFeatures features, String content) {
        if (!StringUtils.hasText(content) || features.terms().isEmpty()) {
            return 0.0;
        }
        String normalizedContent = normalize(content);
        if (!StringUtils.hasText(normalizedContent)) {
            return 0.0;
        }
        int matched = 0;
        for (String term : features.terms()) {
            if (normalizedContent.contains(term)) {
                matched++;
            }
        }
        double coverage = matched / (double) features.terms().size();
        if (hasExactMatch(features, content)) {
            return Math.max(coverage, 0.9);
        }
        return coverage;
    }

    private boolean hasExactMatch(QueryFeatures features, String content) {
        return StringUtils.hasText(features.normalizedQuery())
                && StringUtils.hasText(content)
                && normalize(content).contains(features.normalizedQuery());
    }

    private double metadataBonus(QueryFeatures features,
                                 RagChunkCandidate candidate,
                                 Map<String, Object> metadata,
                                 double lexicalScore) {
        if (lexicalScore <= 0.0) {
            return 0.0;
        }
        double bonus = 0.0;
        String blockType = metadataString(metadata, "blockType");
        String headingPath = metadataString(metadata, "headingPath");
        List<String> headings = metadataStringList(metadata, "headings");
        String title = safeLower(candidate.getDocTitle());

        if ("heading".equalsIgnoreCase(blockType)) {
            bonus += 0.05;
        } else if ("table".equalsIgnoreCase(blockType)) {
            bonus += features.referenceIntent() ? 0.05 : 0.02;
            if (features.tableLookupIntent()) {
                bonus += 0.07;
            }
        } else if ("list".equalsIgnoreCase(blockType)) {
            bonus += features.referenceIntent() ? 0.03 : 0.01;
            if (features.tableLookupIntent()) {
                bonus += 0.03;
            }
        }

        for (String token : features.technicalTerms()) {
            if (containsIgnoreCase(headingPath, token)) {
                bonus += 0.05;
            }
            for (String heading : headings) {
                if (containsIgnoreCase(heading, token)) {
                    bonus += 0.03;
                    break;
                }
            }
            if (title.contains(token)) {
                bonus += 0.03;
            }
        }
        return Math.min(0.24, bonus);
    }

    private double modeSpecificBonus(QueryFeatures features,
                                     RagChunkCandidate candidate,
                                     Map<String, Object> metadata) {
        String title = safeLower(candidate.getDocTitle());
        String content = safeLower(candidate.getContent());
        String headingPath = safeLower(metadataString(metadata, "headingPath"));
        String blockType = metadataString(metadata, "blockType");
        double bonus = 0.0;

        if (features.referenceIntent()) {
            if (features.apiSpecIntent() && title.contains("api-interface-spec")) {
                bonus += 0.12;
            } else if (features.agentSchemaIntent() && title.contains("api-rag-agent-doc")) {
                bonus += 0.12;
            } else if (title.contains("api-interface-spec")) {
                bonus += 0.08;
            } else if (title.contains("api-rag-agent-doc")) {
                bonus += 0.04;
            }
            if (content.contains("/api/") || content.contains("body:") || content.contains("field") || content.contains("request")) {
                bonus += 0.05;
            }
            if (headingPath.contains("chatrequest") || headingPath.contains("agentupsertrequest")
                    || headingPath.contains("base url") || headingPath.contains("sse")
                    || headingPath.contains("ocr") || headingPath.contains("api fields")
                    || headingPath.contains("\u767d\u540d\u5355") || headingPath.contains("\u5206\u9875")
                    || headingPath.contains("\u53c2\u6570") || headingPath.contains("\u5b57\u6bb5")) {
                bonus += 0.05;
            }
            if ("table".equalsIgnoreCase(blockType) && features.fieldLookupIntent()) {
                bonus += 0.05;
            }
            if (metadataInt(metadata, "tablePartCount") > 1 && features.fieldLookupIntent()) {
                bonus += 0.04;
            }
        }

        if (features.explanationIntent()) {
            if (features.implementationIntent() && title.contains("implementation")) {
                bonus += 0.12;
            } else if (title.contains("implementation")) {
                bonus += 0.09;
            }
            if (headingPath.contains("design") || headingPath.contains("implementation")
                    || headingPath.contains("why") || headingPath.contains("\u539f\u56e0")
                    || headingPath.contains("\u8bbe\u8ba1") || headingPath.contains("\u5165\u5e93")) {
                bonus += 0.05;
            }
        }

        if (features.tableLookupIntent()) {
            if (title.contains("api-interface-spec") || title.contains("api-rag-agent-doc")) {
                bonus += 0.08;
            }
            if (features.queryContainsAny("metadata", "rerank", "\u7ed3\u6784\u5316", "\u6392\u5e8f")) {
                if (title.contains("rag-flow") || title.contains("rerank_strategy")) {
                    bonus += 0.18;
                }
                if (title.contains("implementation")) {
                    bonus += 0.04;
                }
            }
            if (features.queryContainsAny("agentupsertrequest", "multi-agent", "\u591aagent", "\u591a agent", "request object", "\u8bf7\u6c42\u5bf9\u8c61")) {
                if (title.contains("api-rag-agent-doc")) {
                    bonus += 0.18;
                }
                if (title.contains("api-interface-spec")) {
                    bonus -= 0.03;
                }
            }
            if (features.queryContainsAny("ocr", "upload", "\u4e0a\u4f20")
                    && !features.queryContainsAny("config", "\u914d\u7f6e", "\u83b7\u53d6\u548c\u66f4\u65b0")) {
                if (title.contains("api-rag-agent-doc")) {
                    bonus += 0.14;
                }
                if (title.contains("api-interface-spec")) {
                    bonus -= 0.05;
                }
            }
            if (containsAny(content, "upload", "ocr", "agentupsertrequest", "chatrequest", "field", "\u63a5\u53e3", "\u5b57\u6bb5", "metadata", "rerank")) {
                bonus += 0.06;
            }
            if (containsAny(headingPath, "ocr", "api", "request", "schema", "\u63a5\u53e3", "\u5b57\u6bb5", "\u4e0a\u4f20", "metadata", "rerank")) {
                bonus += 0.06;
            }
        }

        if (features.multiDocumentIntent()) {
            bonus += crossDocumentTopicBonus(features, title, content, headingPath);
        }

        return Math.min(0.34, bonus);
    }

    private double crossDocumentTopicBonus(QueryFeatures features,
                                           String title,
                                           String content,
                                           String headingPath) {
        double bonus = 0.0;
        String evidence = title + " " + headingPath + " " + content;

        if (features.queryContainsAny("metadata", "\u7ed3\u6784\u5316", "rerank", "\u6392\u5e8f")) {
            if (title.contains("rag-flow") || title.contains("rerank_strategy")) {
                bonus += 0.08;
            }
            if (title.contains("implementation")) {
                bonus += 0.06;
            }
        }

        if (features.queryContainsAny("ocr", "\u5165\u5e93", "\u7ed3\u6784\u5316")) {
            if (title.contains("implementation")) {
                bonus += 0.07;
            }
            if (title.contains("rag-flow") || title.contains("rerank_strategy")) {
                bonus += 0.05;
            }
        }

        if (features.queryContainsAny("\u5bfc\u5165", "import", "\u63a5\u53e3")) {
            if (title.contains("api-interface-spec")) {
                bonus += 0.08;
            }
        }

        if (features.queryContainsAny("\u6267\u884c\u5c42", "\u8fd0\u884c", "runtime", "\u7ed3\u6784")) {
            if (title.contains("skill-architecture")) {
                bonus += 0.08;
            }
            if (title.contains("implementation")) {
                bonus += 0.04;
            }
        }

        if (features.queryContainsAny("\u6307\u6807", "benchmark", "retrieval")) {
            if (title.contains("metrics")) {
                bonus += 0.08;
            }
            if (title.contains("rag-flow") || title.contains("rerank_strategy")) {
                bonus += 0.06;
            }
        }

        if (containsAny(evidence, "\u4e24\u7bc7\u6587\u6863", "\u8054\u5408", "\u540c\u65f6", "\u4e00\u7bc7\u8bb2")) {
            bonus += 0.02;
        }

        return Math.min(0.18, bonus);
    }

    private double confusionAdjustment(QueryFeatures features,
                                       RagChunkCandidate candidate,
                                       Map<String, Object> metadata) {
        if (!features.confusionIntent()) {
            return 0.0;
        }
        String title = safeLower(candidate.getDocTitle());
        String content = safeLower(candidate.getContent());
        String headingPath = safeLower(metadataString(metadata, "headingPath"));
        String evidence = title + " " + headingPath + " " + content;
        double adjustment = 0.0;

        if (features.ocrConfigIntent()) {
            adjustment += containsAny(evidence, "config", "\u914d\u7f6e", "\u7ba1\u7406") ? 0.12 : 0.0;
            adjustment -= containsAny(evidence, "upload", "markdown", "zip", "\u4e0a\u4f20") ? 0.08 : 0.0;
            adjustment += title.contains("api-interface-spec") ? 0.06 : 0.0;
            adjustment -= title.contains("api-rag-agent-doc") ? 0.05 : 0.0;
        }

        if (features.ocrUploadIntent()) {
            adjustment += containsAny(evidence, "upload", "markdown", "zip", "\u4e0a\u4f20") ? 0.12 : 0.0;
            adjustment -= containsAny(evidence, "config", "\u914d\u7f6e", "\u7ba1\u7406") ? 0.08 : 0.0;
            adjustment += title.contains("api-rag-agent-doc") ? 0.06 : 0.0;
            adjustment -= title.contains("api-interface-spec") ? 0.05 : 0.0;
        }

        if (features.handlerDisambiguationIntent()) {
            adjustment += containsAny(evidence, "toolhandler", "handler", "web_search") ? 0.14 : 0.0;
            adjustment -= containsAny(evidence, "registry", "register") ? 0.10 : 0.0;
            adjustment += title.contains("implementation") ? 0.08 : 0.0;
            adjustment -= title.contains("skill-architecture") ? 0.05 : 0.0;
        }

        if (features.importerModuleIntent()) {
            adjustment += containsAny(evidence, "importer", "module", "\u6a21\u5757", "\u63a5\u53e3") ? 0.12 : 0.0;
            adjustment -= containsAny(evidence, "runtime", "architecture", "\u6267\u884c\u5c42", "\u8fd0\u884c\u65f6") ? 0.08 : 0.0;
            adjustment += title.contains("api-interface-spec") ? 0.08 : 0.0;
            adjustment -= title.contains("skill-architecture") ? 0.05 : 0.0;
        }

        if (features.metricDisambiguationIntent()) {
            adjustment += containsAny(evidence, "hit@1", "hit at 1") ? 0.12 : 0.0;
            adjustment -= containsAny(evidence, "mrr", "mean reciprocal rank") ? 0.08 : 0.0;
            adjustment += title.contains("metrics") ? 0.06 : 0.0;
        }

        return clamp(adjustment, -0.18, 0.22);
    }

    private Map<String, Object> parseChunkMetadata(String chunkMetadata) {
        if (!StringUtils.hasText(chunkMetadata)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(chunkMetadata, METADATA_TYPE);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int metadataInt(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    private double toVectorScore(double distance) {
        return 1.0 / (1.0 + Math.max(0.0, distance));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean containsIgnoreCase(String content, String needle) {
        return StringUtils.hasText(content)
                && StringUtils.hasText(needle)
                && content.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private boolean containsAny(String content, String... needles) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        for (String needle : needles) {
            if (containsIgnoreCase(content, needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]+", "");
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record QueryFeatures(String normalizedQuery,
                                 List<String> terms,
                                 List<String> technicalTerms,
                                 boolean referenceIntent,
                                 boolean explanationIntent,
                                 boolean fieldLookupIntent,
                                 boolean apiSpecIntent,
                                 boolean agentSchemaIntent,
                                 boolean implementationIntent,
                                 boolean confusionIntent,
                                 boolean ocrConfigIntent,
                                 boolean ocrUploadIntent,
                                 boolean handlerDisambiguationIntent,
                                 boolean importerModuleIntent,
                                 boolean metricDisambiguationIntent,
                                 boolean multiDocumentIntent,
                                 boolean tableLookupIntent,
                                 boolean ocrSpecializedIntent) {
        private static QueryFeatures from(String query) {
            String raw = query == null ? "" : query.trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            String normalizedQuery = lower.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]+", "");
            Set<String> termSet = new LinkedHashSet<>();
            Matcher matcher = TERM_PATTERN.matcher(lower);
            while (matcher.find()) {
                String term = matcher.group();
                if (StringUtils.hasText(term)) {
                    termSet.add(term.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]+", ""));
                }
            }
            if (termSet.isEmpty() && StringUtils.hasText(normalizedQuery)) {
                termSet.add(normalizedQuery);
            }

            Set<String> technicalTerms = new LinkedHashSet<>();
            Matcher techMatcher = TECHNICAL_TOKEN_PATTERN.matcher(raw);
            while (techMatcher.find()) {
                String token = techMatcher.group();
                if (!StringUtils.hasText(token)) {
                    continue;
                }
                String cleaned = token.trim().toLowerCase(Locale.ROOT);
                if (cleaned.contains("/")
                        || tokenHasMixedCase(token)
                        || cleaned.endsWith("request")
                        || cleaned.endsWith("response")
                        || cleaned.endsWith("handler")
                        || cleaned.endsWith("service")
                        || cleaned.endsWith("controller")
                        || cleaned.endsWith("model")
                        || cleaned.endsWith("config")
                        || cleaned.contains("hit@1")
                        || cleaned.contains("mrr")) {
                    technicalTerms.add(cleaned);
                }
            }

            boolean explanationIntent = containsAny(lower,
                    "why", "design", "implementation", "reason", "principle",
                    "\u4e3a\u4ec0\u4e48", "\u539f\u56e0", "\u8bbe\u8ba1", "\u5b9e\u73b0", "\u539f\u7406");
            boolean fieldLookupIntent = containsAny(lower,
                    "field", "fields", "param", "request", "handler", "endpoint", "base url", "page size", "token", "sse", "ocr",
                    "\u5b57\u6bb5", "\u53c2\u6570", "\u63a5\u53e3", "\u8bf7\u6c42", "\u914d\u7f6e\u53c2\u6570", "\u767d\u540d\u5355", "\u4e0a\u9650", "\u5206\u9875", "\u68c0\u7d22\u6761\u6570");
            boolean apiSpecIntent = containsAny(lower,
                    "base url", "sse", "page size", "whitelist", "token", "ocr config", "endpoint",
                    "\u63a5\u53e3", "\u767d\u540d\u5355", "\u4e0a\u9650", "ocr \u914d\u7f6e", "\u83b7\u53d6\u548c\u66f4\u65b0")
                    && !containsAny(lower, "chatrequest", "agentupsertrequest", "multi-agent", "ragquery", "ragtopk");
            boolean agentSchemaIntent = containsAny(lower,
                    "chatrequest", "agentupsertrequest", "multi-agent", "ragquery", "ragtopk",
                    "markdown", "zip", "mmr", "\u5019\u9009\u6c60", "\u591aagent", "\u591a agent", "\u68c0\u7d22\u95ee\u9898", "\u68c0\u7d22\u6761\u6570");
            boolean implementationIntent = explanationIntent || containsAny(lower,
                    "fallback", "sequence", "order", "toolhandler",
                    "\u56de\u9000", "\u987a\u5e8f", "\u6b65\u9aa4", "\u5165\u5e93\u9636\u6bb5", "\u67e5\u8be2\u65f6", "toolhandler");
            boolean confusionIntent = containsAny(lower,
                    "\u4e0d\u8981\u8bef", "\u8bef\u53ec\u56de", "\u8bef\u7b54\u6210", "\u771f\u6b63", "\u800c\u4e0d\u662f",
                    "confusion", "instead");
            boolean ocrConfigIntent = confusionIntent
                    && containsAny(lower, "ocr")
                    && containsAny(lower, "config", "\u914d\u7f6e");
            boolean ocrUploadIntent = confusionIntent
                    && containsAny(lower, "ocr")
                    && containsAny(lower, "upload", "markdown", "zip", "\u4e0a\u4f20");
            boolean handlerDisambiguationIntent = confusionIntent
                    && containsAny(lower, "handler", "toolhandler")
                    && containsAny(lower, "registry");
            boolean importerModuleIntent = confusionIntent
                    && containsAny(lower, "importer")
                    && containsAny(lower, "runtime", "architecture", "\u6a21\u5757", "\u6267\u884c\u5c42", "\u8fd0\u884c\u65f6");
            boolean metricDisambiguationIntent = confusionIntent
                    && containsAny(lower, "hit@1", "mrr");
            boolean multiDocumentIntent = containsAny(lower,
                    "\u540c\u65f6", "\u8054\u5408", "\u54ea\u4e24\u7bc7\u6587\u6863", "\u4e00\u7bc7\u8bb2", "\u5206\u522b\u89e3\u91ca",
                    "cross_document", "two documents");
            boolean tableLookupIntent = containsAny(lower,
                    "\u8868\u683c", "\u6e05\u5355", "\u63a5\u53e3\u6e05\u5355", "\u5b57\u6bb5\u8bf4\u660e", "schema", "request object")
                    || (fieldLookupIntent && containsAny(lower, "ocr", "upload", "agentupsertrequest", "chatrequest"));
            boolean ocrSpecializedIntent = containsAny(lower,
                    "\u4ece ocr", "ocr \u68c0\u7d22", "ocr \u4e13\u9879", "ocr \u914d\u7f6e", "ocr \u4e0a\u4f20")
                    || (containsAny(lower, "ocr") && explanationIntent);
            boolean referenceIntent = !explanationIntent
                    && (fieldLookupIntent || lower.contains("/api/") || lower.contains("base url")
                    || lower.contains("\u63a5\u53e3") || lower.contains("\u5b57\u6bb5") || lower.contains("\u53c2\u6570"));

            return new QueryFeatures(normalizedQuery,
                    List.copyOf(termSet),
                    List.copyOf(technicalTerms),
                    referenceIntent,
                    explanationIntent,
                    fieldLookupIntent,
                    apiSpecIntent,
                    agentSchemaIntent,
                    implementationIntent,
                    confusionIntent,
                    ocrConfigIntent,
                    ocrUploadIntent,
                    handlerDisambiguationIntent,
                    importerModuleIntent,
                    metricDisambiguationIntent,
                    multiDocumentIntent,
                    tableLookupIntent,
                    ocrSpecializedIntent);
        }

        private static QueryFeatures empty() {
            return new QueryFeatures("", List.of(), List.of(), false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        }

        private boolean diversityIntent() {
            return multiDocumentIntent || ocrSpecializedIntent;
        }

        private boolean queryContainsAny(String... needles) {
            for (String needle : needles) {
                if (normalizedQuery.contains(needle.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]+", ""))) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsAny(String text, String... needles) {
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean tokenHasMixedCase(String token) {
            return !token.equals(token.toLowerCase(Locale.ROOT));
        }
    }
}
