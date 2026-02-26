package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.harmony.backend.ai.rag.model.RagChunkCandidate;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.response.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnBean(name = "ragJdbcTemplate")
@RequiredArgsConstructor
@Slf4j
public class RagServiceImpl implements RagService {
    private static final Pattern MARKDOWN_IMAGE_PATTERN =
            Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private final RagRepository ragRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;
    private final OcrService ocrService;

    @Override
    public String ingest(Long userId, String title, String content) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(400, "Content is required");
        }
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "Untitled";
        String docId = ragRepository.createDocument(userId, safeTitle, content);
        List<String> chunks = splitContent(content, properties.getChunkSize(), properties.getChunkOverlap());
        for (String chunk : chunks) {
            float[] embedding = embeddingService.embed(chunk);
            ragRepository.insertChunk(docId, userId, chunk, embedding);
        }
        return docId;
    }

    @Override
    public String ingestMarkdown(Long userId, String title, String markdownContent, String sourcePath) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(markdownContent)) {
            throw new BusinessException(400, "Markdown content is required");
        }
        String safeTitle = StringUtils.hasText(title) ? title.trim() : deriveTitleFromSource(sourcePath);
        String enriched = enrichMarkdownWithImageRefs(markdownContent, sourcePath);
        return ingest(userId, safeTitle, enriched);
    }

    @Override
    public String ingestMarkdownWithImages(Long userId, String title, String markdownContent,
                                           java.util.Map<String, byte[]> images) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(markdownContent)) {
            throw new BusinessException(400, "Markdown content is required");
        }
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "Markdown Note";
        String enriched = embedImages(markdownContent, images);
        return ingest(userId, safeTitle, enriched);
    }

    @Override
    public List<RagChunkMatch> search(Long userId, String query, Integer topK) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(400, "Query is required");
        }
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        float[] embedding = embeddingService.embed(query);
        RagProperties.Search search = properties.getSearch();
        if (search != null && "mmr".equalsIgnoreCase(search.getStrategy())) {
            List<RagChunkMatch> mmrMatches = mmrSearch(userId, embedding, limit, search);
            if (mmrMatches != null && !mmrMatches.isEmpty()) {
                return mmrMatches;
            }
            List<RagChunkMatch> fallback = ragRepository.search(userId, embedding, limit);
            return filterByMinScore(fallback, search.getMinScore());
        }
        List<RagChunkMatch> matches = ragRepository.search(userId, embedding, limit);
        return filterByMinScore(matches, search != null ? search.getMinScore() : 0.0);
    }

    @Override
    public String buildContext(Long userId, String query, Integer topK) {
        List<RagChunkMatch> matches = search(userId, query, topK);
        if (matches.isEmpty()) {
            List<String> fallback = keywordFallback(userId, query, topK);
            if (!fallback.isEmpty()) {
                log.info("RAG fallback hit. query='{}' snippets={}", query, fallback.size());
            }
            if (fallback.isEmpty()) {
                log.info("RAG fallback miss. query='{}'", query);
                return "";
            }
            return buildSnippetContext(fallback, query);
        }
        List<String> keywords = extractKeywords(query);
        List<String> mustHave = extractMustHaveKeywordsV2(query, keywords);
        boolean keywordHit = hasKeywordHit(matches, query, keywords, mustHave);
        log.info("RAG keywords: query='{}' keywords={} mustHave={}", query, keywords, mustHave);
        logTopMatches(matches);
        if (!keywordHit) {
            List<String> fallback = keywordFallback(userId, query, topK);
            if (!fallback.isEmpty()) {
                log.info("RAG vector hit but keyword miss. query='{}' matches={}, fallback={}", query, matches.size(), fallback.size());
                return buildSnippetContext(fallback, query);
            }
            RagProperties.Search search = properties.getSearch();
            double minScore = search != null ? search.getMinScore() : 0.0;
            if (shouldTrustVector(matches, minScore, query)) {
                log.info("RAG vector hit without keyword. query='{}' matches={}", query, matches.size());
                StringBuilder sb = new StringBuilder();
                for (RagChunkMatch match : matches) {
                    sb.append(match.getContent()).append("\n");
                }
                return sb.toString().trim();
            }
            log.info("RAG vector hit but keyword miss and fallback empty. query='{}' matches={}", query, matches.size());
            return "";
        }
        log.info("RAG vector search hit. query='{}' matches={}", query, matches.size());
        return buildContextFromMatches(matches);
    }

    @Override
    public PageResult<RagDocumentSummary> listDocuments(Long userId, int page, int size) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        int finalPage = Math.max(page, 1);
        int finalSize = Math.max(size, 1);
        int offset = (finalPage - 1) * finalSize;
        long total = ragRepository.countDocuments(userId);
        List<RagDocumentSummary> docs = ragRepository.listDocuments(userId, offset, finalSize);
        return buildPageResult(docs, total, finalPage, finalSize);
    }

    @Override
    public PageResult<RagDocumentSummary> listDocumentsForAdmin(Long userId, int page, int size) {
        int finalPage = Math.max(page, 1);
        int finalSize = Math.max(size, 1);
        int offset = (finalPage - 1) * finalSize;
        if (userId == null) {
            long total = ragRepository.countAllDocuments();
            List<RagDocumentSummary> docs = ragRepository.listAllDocuments(offset, finalSize);
            return buildPageResult(docs, total, finalPage, finalSize);
        }
        long total = ragRepository.countDocuments(userId);
        List<RagDocumentSummary> docs = ragRepository.listDocuments(userId, offset, finalSize);
        return buildPageResult(docs, total, finalPage, finalSize);
    }

    @Override
    public boolean deleteDocument(Long userId, String docId) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(docId)) {
            throw new BusinessException(400, "docId is required");
        }
        return ragRepository.deleteDocument(userId, docId);
    }

    @Override
    public boolean deleteDocumentForAdmin(String docId) {
        if (!StringUtils.hasText(docId)) {
            throw new BusinessException(400, "docId is required");
        }
        return ragRepository.deleteDocumentAdmin(docId);
    }

    private PageResult<RagDocumentSummary> buildPageResult(List<RagDocumentSummary> docs,
                                                           long total,
                                                           int page,
                                                           int size) {
        PageResult<RagDocumentSummary> result = new PageResult<>();
        result.setContent(docs);
        result.setTotalElements(total);
        result.setPageNumber(page);
        result.setPageSize(size);
        result.setTotalPages((int) Math.ceil(total / (double) size));
        return result;
    }

    private List<String> splitContent(String content, int chunkSize, int overlap) {
        int size = Math.max(200, chunkSize);
        int step = Math.max(50, size - Math.max(0, overlap));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + size);
            String chunk = content.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            start += step;
        }
        return chunks;
    }

    private List<String> keywordFallback(Long userId, String query, Integer topK) {
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        LinkedHashSet<String> results = new LinkedHashSet<>();
        if (StringUtils.hasText(query)) {
            String normalized = query.trim();
            results.addAll(ragRepository.searchChunksByKeyword(userId, normalized, limit));
            if (results.size() < limit) {
                results.addAll(ragRepository.searchByKeyword(userId, normalized, limit));
            }
            if (results.size() >= limit) {
                return new ArrayList<>(results);
            }
            for (String keyword : extractKeywords(query)) {
                results.addAll(ragRepository.searchChunksByKeyword(userId, keyword, limit));
                if (results.size() >= limit) {
                    break;
                }
                results.addAll(ragRepository.searchByKeyword(userId, keyword, limit));
                if (results.size() >= limit) {
                    break;
                }
            }
        }
        if (results.isEmpty()) {
            results.addAll(inMemoryKeywordFallback(userId, query, limit));
        }
        return new ArrayList<>(results);
    }

    private List<String> extractKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String trimmed = normalizeQueryV2(query);
        List<String> keywords = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\p{IsHan}]{2,}|[A-Za-z0-9_]{3,})").matcher(trimmed);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (StringUtils.hasText(token)) {
                keywords.add(token);
            }
        }
        if (!keywords.isEmpty()) {
            return filterStopwordsV2(expandChineseKeywords(keywords));
        }
        // Fallback: allow single Han characters for very short or single-term queries.
        String compact = trimmed.replaceAll("\\s+", "");
        if (compact.length() <= 3) {
            for (int i = 0; i < compact.length(); i++) {
                char c = compact.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FFF) {
                    keywords.add(String.valueOf(c));
                }
            }
        }
        return filterStopwordsV2(keywords);
    }

    private String normalizeQuery(String query) {
        String trimmed = query.trim();
        trimmed = trimmed.replaceAll("[“”\"'！？!?。，,.；;：:（）()【】\\[\\]]", " ");
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        String[] prefixes = {"介绍一下", "介绍", "什么是", "请介绍", "讲一下", "讲讲", "说明一下", "解释一下"};
        for (String prefix : prefixes) {
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length()).trim();
                break;
            }
        }
        return trimmed;
    }

    private List<String> expandChineseKeywords(List<String> base) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>(base);
        for (String token : base) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String compact = token.replaceAll("\\s+", "");
            if (compact.length() <= 1) {
                expanded.add(compact);
                continue;
            }
            if (compact.length() <= 3) {
                for (int i = 0; i < compact.length(); i++) {
                    char c = compact.charAt(i);
                    if (c >= 0x4E00 && c <= 0x9FFF) {
                        expanded.add(String.valueOf(c));
                    }
                }
            }
            if (compact.length() >= 3) {
                for (int i = 0; i < compact.length() - 1; i++) {
                    String bi = compact.substring(i, i + 2);
                    expanded.add(bi);
                }
            }
        }
        return new ArrayList<>(expanded);
    }

    private List<String> filterStopwords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
                Set<String> stopwords = Set.of(
                "介绍", "一下", "讲讲", "讲", "说明", "解释", "如何", "是什么", "什么是",
                "存在", "系统", "相关", "主要", "内容", "信息", "问题", "为什么", "怎么",
                "哪个", "哪些", "是否", "可以", "需要", "应该", "有没有"
        );
        List<String> filtered = new ArrayList<>();
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            String compact = keyword.trim();
            if (stopwords.contains(compact)) {
                continue;
            }
            filtered.add(compact);
        }
        return filtered;
    }

    private String buildSnippetContext(List<String> contents, String query) {
        StringBuilder sb = new StringBuilder();
        List<String> keywords = extractKeywords(query);
        int maxSnippet = Math.max(400, properties.getChunkSize());
        for (String content : contents) {
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String snippet = extractSnippet(content, keywords, maxSnippet);
            sb.append(snippet).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildContextFromMatches(List<RagChunkMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        int maxChars = Math.max(2000, properties.getChunkSize() * 6);
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (RagChunkMatch match : matches) {
            if (match == null || !StringUtils.hasText(match.getContent())) {
                continue;
            }
            String content = match.getContent().trim();
            if (seen.contains(content)) {
                continue;
            }
            seen.add(content);
            if (sb.length() + content.length() + 1 > maxChars) {
                int remaining = maxChars - sb.length();
                if (remaining > 0) {
                    sb.append(content, 0, Math.min(remaining, content.length()));
                }
                break;
            }
            sb.append(content).append("\n");
        }
        return sb.toString().trim();
    }

    private String extractSnippet(String content, List<String> keywords, int maxLen) {
        String text = content.trim();
        if (text.length() <= maxLen) {
            return text;
        }
        String lower = text.toLowerCase();
        int index = -1;
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            int idx = lower.indexOf(keyword.toLowerCase());
            if (idx >= 0) {
                index = idx;
                break;
            }
        }
        if (index < 0) {
            return text.substring(0, maxLen);
        }
        int start = Math.max(0, index - maxLen / 3);
        int end = Math.min(text.length(), start + maxLen);
        return text.substring(start, end);
    }

    private boolean hasKeywordHit(List<RagChunkMatch> matches,
                                  String query,
                                  List<String> keywords,
                                  List<String> mustHave) {
        if ((keywords == null || keywords.isEmpty()) && !StringUtils.hasText(query)) {
            return false;
        }
        for (RagChunkMatch match : matches) {
            if (match == null || !StringUtils.hasText(match.getContent())) {
                continue;
            }
            String content = match.getContent();
            if (mustHave != null && !mustHave.isEmpty()) {
                boolean mustHit = false;
                for (String keyword : mustHave) {
                    if (containsIgnoreCase(content, keyword)) {
                        mustHit = true;
                        break;
                    }
                }
                if (!mustHit) {
                    continue;
                }
            }
            if (containsIgnoreCase(content, query)) {
                return true;
            }
            for (String keyword : keywords) {
                if (containsIgnoreCase(content, keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> extractMustHaveKeywords(String query, List<String> keywords) {
        List<String> mustHave = new ArrayList<>();
        if (!StringUtils.hasText(query)) {
            return mustHave;
        }
        String normalized = normalizeQuery(query);
        Matcher latin = Pattern.compile("([A-Za-z0-9]{3,})").matcher(normalized);
        while (latin.find()) {
            String token = latin.group(1);
            if (StringUtils.hasText(token)) {
                mustHave.add(token);
            }
        }
        if (!mustHave.isEmpty()) {
            return mustHave;
        }
        if (keywords == null || keywords.isEmpty()) {
            return mustHave;
        }
        String longest = "";
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            if (keyword.length() > longest.length()) {
                longest = keyword;
            }
        }
        if (StringUtils.hasText(longest)) {
            mustHave.add(longest);
        }
        return mustHave;
    }

    private boolean shouldTrustVector(List<RagChunkMatch> matches, double minScore, String query) {
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        double threshold = Math.min(0.4, Math.max(minScore + 0.1, 0.2));
        if (StringUtils.hasText(query) && query.trim().length() <= 2) {
            threshold = Math.min(0.3, threshold);
        }
        for (RagChunkMatch match : matches) {
            if (match != null && match.getScore() >= threshold) {
                return true;
            }
        }
        return false;
    }

    private String normalizeQueryV2(String query) {
        String trimmed = query == null ? "" : query.trim();
        trimmed = trimmed.replaceAll("[“”\"'’‘，,。；;：:！!？?（）()【】\\[\\]<>《》]", " ");
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        String[] prefixes = {
                "介绍一下", "介绍下", "介绍", "什么是", "请介绍", "讲一下", "讲讲", "说明一下", "解释一下", "讲解一下"
        };
        for (String prefix : prefixes) {
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length()).trim();
                break;
            }
        }
        return trimmed;
    }

    private List<String> filterStopwordsV2(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> stopwords = Set.of(
                "介绍", "介绍一下", "介绍下", "讲一下", "讲讲", "说明", "说明一下", "解释", "解释一下",
                "如何", "是什么", "什么是", "是否", "可以", "需要", "应该", "有没有", "怎么",
                "哪个", "哪些", "主要", "内容", "信息", "相关", "问题", "存在", "系统"
        );
        List<String> filtered = new ArrayList<>();
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            String compact = keyword.trim();
            if (stopwords.contains(compact)) {
                continue;
            }
            filtered.add(compact);
        }
        return filtered;
    }

    private List<String> extractMustHaveKeywordsV2(String query, List<String> keywords) {
        List<String> mustHave = new ArrayList<>();
        if (!StringUtils.hasText(query)) {
            return mustHave;
        }
        String normalized = normalizeQueryV2(query);
        Matcher latin = Pattern.compile("([A-Za-z0-9]{3,})").matcher(normalized);
        while (latin.find()) {
            String token = latin.group(1);
            if (StringUtils.hasText(token)) {
                mustHave.add(token);
            }
        }
        return mustHave;
    }

        private void logTopMatches(List<RagChunkMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        int limit = Math.min(3, matches.size());
        for (int i = 0; i < limit; i++) {
            RagChunkMatch match = matches.get(i);
            if (match == null || !StringUtils.hasText(match.getContent())) {
                continue;
            }
            String content = match.getContent().replaceAll("\\s+", " ");
            if (content.length() > 200) {
                content = content.substring(0, 200) + "...";
            }
            log.info("RAG top match[{}]: {}", i, content);
        }
    }

    private List<String> inMemoryKeywordFallback(Long userId, String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        List<String> keywords = extractKeywords(query);
        List<String> results = new ArrayList<>();
        List<String> docContents = ragRepository.listRecentDocumentContents(userId, 50);
        collectMatches(results, docContents, query, keywords, limit);
        if (results.size() >= limit) {
            return results;
        }
        List<String> chunkContents = ragRepository.listRecentChunkContents(userId, 80);
        collectMatches(results, chunkContents, query, keywords, limit);
        return results;
    }

    private void collectMatches(List<String> results,
                                List<String> contents,
                                String query,
                                List<String> keywords,
                                int limit) {
        if (contents == null || contents.isEmpty() || results.size() >= limit) {
            return;
        }
        String normalizedQuery = normalizeText(query);
        for (String content : contents) {
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String normalizedContent = normalizeText(content);
            if (containsIgnoreCase(content, query) || (normalizedQuery.length() > 0 && normalizedContent.contains(normalizedQuery))) {
                results.add(content);
            } else {
                for (String keyword : keywords) {
                    if (containsIgnoreCase(content, keyword)) {
                        results.add(content);
                        break;
                    }
                }
            }
            if (results.size() >= limit) {
                break;
            }
        }
    }

    private boolean containsIgnoreCase(String content, String needle) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(needle)) {
            return false;
        }
        return content.toLowerCase().contains(needle.toLowerCase());
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", "");
    }

    private String deriveTitleFromSource(String sourcePath) {
        if (!StringUtils.hasText(sourcePath)) {
            return "Markdown Note";
        }
        String normalized = sourcePath.replace("\\", "/");
        int idx = normalized.lastIndexOf('/');
        if (idx >= 0 && idx < normalized.length() - 1) {
            return normalized.substring(idx + 1);
        }
        return normalized;
    }

    private String enrichMarkdownWithImageRefs(String markdownContent, String sourcePath) {
        Set<String> refs = extractImageRefs(markdownContent, sourcePath);
        if (refs.isEmpty()) {
            return markdownContent;
        }
        StringBuilder sb = new StringBuilder(markdownContent.trim());
        sb.append("\n\n---\nImage references");
        if (StringUtils.hasText(sourcePath)) {
            sb.append(" (source: ").append(sourcePath.trim()).append(")");
        }
        sb.append(":\n");
        for (String ref : refs) {
            sb.append("- ").append(ref).append('\n');
        }
        return sb.toString().trim();
    }

    private String embedImages(String markdownContent, java.util.Map<String, byte[]> images) {
        if (images == null || images.isEmpty()) {
            return markdownContent;
        }
        String enriched = markdownContent;
        java.util.Map<String, String> ocrMap = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, byte[]> entry : images.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (!StringUtils.hasText(name) || bytes == null || bytes.length == 0) {
                continue;
            }
            String ocrText = ocrService.extractText(bytes, name, "image/*");
            if (StringUtils.hasText(ocrText)) {
                ocrMap.put(name.toLowerCase(), ocrText.trim());
            }
        }
        String withInlineOcr = insertOcrAfterImages(enriched, ocrMap);
        StringBuilder sb = new StringBuilder(withInlineOcr.trim());
        sb.append("\n\n---\nImage attachments\n");
        for (java.util.Map.Entry<String, byte[]> entry : images.entrySet()) {
            sb.append("- ").append(entry.getKey()).append("\n");
        }
        return sb.toString().trim();
    }

    private String insertOcrAfterImages(String markdownContent, java.util.Map<String, String> ocrMap) {
        if (ocrMap == null || ocrMap.isEmpty() || !StringUtils.hasText(markdownContent)) {
            return markdownContent;
        }
        StringBuilder output = new StringBuilder();
        java.util.Set<String> used = new java.util.HashSet<>();
        java.util.regex.Pattern md = java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
        java.util.regex.Matcher mdMatcher = md.matcher(markdownContent);
        int last = 0;
        while (mdMatcher.find()) {
            output.append(markdownContent, last, mdMatcher.end());
            String path = mdMatcher.group(1);
            String name = normalizeImageName(path);
            String ocr = ocrMap.get(name);
            if (ocr != null && !ocr.isBlank()) {
                output.append("\n\n[OCR: ").append(name).append("]\n").append(ocr).append("\n");
                used.add(name);
            }
            last = mdMatcher.end();
        }
        output.append(markdownContent.substring(last));

        java.util.regex.Pattern wiki = java.util.regex.Pattern.compile("!\\[\\[([^\\]]+)\\]\\]");
        java.util.regex.Matcher wikiMatcher = wiki.matcher(output.toString());
        StringBuffer sb = new StringBuffer();
        while (wikiMatcher.find()) {
            String raw = wikiMatcher.group(1);
            String name = normalizeImageName(raw);
            String ocr = ocrMap.get(name);
            String replacement = wikiMatcher.group(0);
            if (ocr != null && !ocr.isBlank()) {
                replacement = replacement + "\n\n[OCR: " + name + "]\n" + ocr + "\n";
                used.add(name);
            }
            wikiMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        wikiMatcher.appendTail(sb);

        java.util.regex.Pattern html = java.util.regex.Pattern.compile("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher htmlMatcher = html.matcher(sb.toString());
        StringBuffer sb2 = new StringBuffer();
        while (htmlMatcher.find()) {
            String raw = htmlMatcher.group(1);
            String name = normalizeImageName(raw);
            String ocr = ocrMap.get(name);
            String replacement = htmlMatcher.group(0);
            if (ocr != null && !ocr.isBlank()) {
                replacement = replacement + "\n\n[OCR: " + name + "]\n" + ocr + "\n";
                used.add(name);
            }
            htmlMatcher.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        htmlMatcher.appendTail(sb2);

        java.util.List<String> leftover = new java.util.ArrayList<>();
        for (String key : ocrMap.keySet()) {
            if (!used.contains(key)) {
                leftover.add(key);
            }
        }
        if (!leftover.isEmpty()) {
            sb2.append("\n\n---\nImage OCR (unreferenced)\n");
            for (String key : leftover) {
                sb2.append("[OCR: ").append(key).append("]\n").append(ocrMap.get(key)).append("\n");
            }
        }
        return sb2.toString();
    }

    private String normalizeImageName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String clean = raw.trim();
        int pipeIdx = clean.indexOf("|");
        if (pipeIdx > 0) {
            clean = clean.substring(0, pipeIdx);
        }
        int hashIdx = clean.indexOf("#");
        if (hashIdx > 0) {
            clean = clean.substring(0, hashIdx);
        }
        clean = clean.replace("\\", "/");
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            try {
                String path = java.net.URI.create(clean).getPath();
                if (path != null) {
                    clean = path;
                }
            } catch (Exception ignored) {
            }
        }
        String name = java.nio.file.Paths.get(clean).getFileName().toString();
        return name == null ? "" : name.toLowerCase();
    }

    private Set<String> extractImageRefs(String markdownContent, String sourcePath) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(markdownContent);
        Path baseDir = null;
        if (StringUtils.hasText(sourcePath)) {
            try {
                Path source = Paths.get(sourcePath).normalize();
                baseDir = source.getParent();
            } catch (Exception ignored) {
                baseDir = null;
            }
        }
        while (matcher.find()) {
            String path = matcher.group(1);
            if (StringUtils.hasText(path)) {
                String normalized = path.trim();
                if (baseDir != null) {
                    try {
                        Path resolved = baseDir.resolve(normalized).normalize();
                        refs.add(resolved.toString().replace("\\", "/"));
                        continue;
                    } catch (Exception ignored) {
                    }
                }
                refs.add(normalized);
            }
        }
        return refs;
    }

    private List<RagChunkMatch> mmrSearch(Long userId,
                                          float[] queryEmbedding,
                                          int topK,
                                          RagProperties.Search search) {
        int multiplier = Math.max(1, search.getMmrCandidateMultiplier());
        int candidateLimit = Math.max(topK * multiplier, topK);
        List<RagChunkCandidate> candidates = ragRepository.searchCandidates(userId, queryEmbedding, candidateLimit);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        double lambda = clamp(search.getMmrLambda(), 0.0, 1.0);
        double minScore = Math.max(0.0, search.getMinScore());
        List<RagChunkMatch> selected = new ArrayList<>();
        List<float[]> selectedEmbeddings = new ArrayList<>();

        for (int i = 0; i < topK; i++) {
            int bestIdx = -1;
            double bestScore = -Double.MAX_VALUE;
            double bestQuerySim = 0.0;
            for (int idx = 0; idx < candidates.size(); idx++) {
                RagChunkCandidate candidate = candidates.get(idx);
                if (candidate == null || candidate.getEmbedding() == null) {
                    continue;
                }
                double querySim = 1.0 / (1.0 + Math.max(0.0, candidate.getDistance()));
                if (querySim < minScore) {
                    continue;
                }
                double maxSimToSelected = 0.0;
                for (float[] selectedEmbedding : selectedEmbeddings) {
                    maxSimToSelected = Math.max(maxSimToSelected, cosineSimilarity(candidate.getEmbedding(), selectedEmbedding));
                }
                double mmrScore = lambda * querySim - (1.0 - lambda) * maxSimToSelected;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestIdx = idx;
                    bestQuerySim = querySim;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            RagChunkCandidate best = candidates.get(bestIdx);
            selected.add(new RagChunkMatch(best.getDocId(), best.getContent(), bestQuerySim));
            selectedEmbeddings.add(best.getEmbedding());
            candidates.set(bestIdx, null);
        }
        return selected;
    }

    private List<RagChunkMatch> filterByMinScore(List<RagChunkMatch> matches, double minScore) {
        if (matches == null || matches.isEmpty() || minScore <= 0.0) {
            return matches == null ? List.of() : matches;
        }
        List<RagChunkMatch> filtered = new ArrayList<>();
        for (RagChunkMatch match : matches) {
            if (match != null && match.getScore() >= minScore) {
                filtered.add(match);
            }
        }
        return filtered;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}



