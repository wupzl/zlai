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
import com.harmony.backend.common.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
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
    private static final Pattern PARAGRAPH_SPLIT_PATTERN = Pattern.compile("\\n\\s*\\n+");

    private final RagRepository ragRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;
    private final OcrService ocrService;

    @Override
    @Transactional(transactionManager = "ragTransactionManager", rollbackFor = Exception.class)
    public String ingest(Long userId, String title, String content) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(400, "Content is required");
        }
        String safeTitle = StringUtils.hasText(title) ? title.trim() : "Untitled";
        String docId = ragRepository.createDocument(userId, safeTitle, content);
        List<String> chunks = splitContent(content);
        for (String chunk : chunks) {
            float[] embedding = embeddingService.embed(chunk);
            ragRepository.insertChunk(docId, userId, chunk, embedding);
        }
        return docId;
    }

    @Override
    @Transactional(transactionManager = "ragTransactionManager", rollbackFor = Exception.class)
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
    @Transactional(transactionManager = "ragTransactionManager", rollbackFor = Exception.class)
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
    @Transactional(transactionManager = "ragTransactionManager", rollbackFor = Exception.class)
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
    @Transactional(transactionManager = "ragTransactionManager", rollbackFor = Exception.class)
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

    private List<String> splitContent(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        int targetTokens = Math.max(120, properties.getChunkTokenSize());
        int overlapTokens = Math.max(0, Math.min(properties.getChunkTokenOverlap(), targetTokens / 2));
        List<String> segments = splitIntoSegments(content);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String segment : segments) {
            String normalized = segment == null ? "" : segment.trim();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            int segmentTokens = TokenCounter.estimateTokens(normalized);
            if (segmentTokens > targetTokens) {
                if (currentTokens > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                    currentTokens = 0;
                }
                chunks.addAll(splitLargeSegment(normalized, targetTokens, overlapTokens));
                continue;
            }
            if (currentTokens > 0 && currentTokens + segmentTokens > targetTokens) {
                chunks.add(current.toString().trim());
                current.setLength(0);
                currentTokens = 0;
            }
            if (currentTokens > 0) {
                current.append("\n\n");
            }
            current.append(normalized);
            currentTokens = TokenCounter.estimateTokens(current.toString());
        }
        if (currentTokens > 0) {
            chunks.add(current.toString().trim());
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
        String trimmed = normalizeQueryClean(query);
        List<String> keywords = new ArrayList<>();
        Matcher matcher = Pattern.compile("([\\p{IsHan}]{2,}|[A-Za-z0-9_]{3,})").matcher(trimmed);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (StringUtils.hasText(token)) {
                keywords.add(token);
            }
        }
        if (!keywords.isEmpty()) {
            return filterStopwordsClean(expandChineseKeywords(keywords));
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
        return filterStopwordsClean(keywords);
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


    private String buildSnippetContext(List<String> contents, String query) {
        StringBuilder sb = new StringBuilder();
        List<String> keywords = extractKeywords(query);
        int maxSnippetTokens = Math.max(80, properties.getSnippetMaxTokens());
        int contextBudgetTokens = Math.max(maxSnippetTokens, properties.getContextMaxTokens());
        int usedTokens = 0;
        for (String content : contents) {
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String snippet = trimToTokenBudget(extractSnippet(content, keywords, maxSnippetTokens * 4), maxSnippetTokens);
            int snippetTokens = TokenCounter.estimateTokens(snippet);
            if (!StringUtils.hasText(snippet) || snippetTokens <= 0) {
                continue;
            }
            if (usedTokens > 0 && usedTokens + snippetTokens > contextBudgetTokens) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(snippet);
            usedTokens += snippetTokens;
        }
        return sb.toString().trim();
    }

    private String buildContextFromMatches(List<RagChunkMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int maxTokens = Math.max(properties.getChunkTokenSize(), properties.getContextMaxTokens());
        int usedTokens = 0;
        for (RagChunkMatch match : matches) {
            if (match == null || !StringUtils.hasText(match.getContent())) {
                continue;
            }
            String content = match.getContent().trim();
            if (seen.contains(content)) {
                continue;
            }
            seen.add(content);
            int contentTokens = TokenCounter.estimateTokens(content);
            if (contentTokens <= 0) {
                continue;
            }
            if (usedTokens >= maxTokens) {
                break;
            }
            String next = content;
            if (usedTokens + contentTokens > maxTokens) {
                next = trimToTokenBudget(content, maxTokens - usedTokens);
                contentTokens = TokenCounter.estimateTokens(next);
            }
            if (!StringUtils.hasText(next) || contentTokens <= 0) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(next);
            usedTokens += contentTokens;
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

    private List<String> splitIntoSegments(String content) {
        String normalized = content.replace("\r\n", "\n").trim();
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCodeBlock = false;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (current.length() > 0 && !inCodeBlock) {
                    addParagraphSegments(segments, current.toString());
                    current.setLength(0);
                }
                current.append(line).append("\n");
                inCodeBlock = !inCodeBlock;
                if (!inCodeBlock) {
                    segments.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            if (inCodeBlock) {
                current.append(line).append("\n");
                continue;
            }
            if (trimmed.isEmpty()) {
                addParagraphSegments(segments, current.toString());
                current.setLength(0);
                continue;
            }
            if (current.length() > 0) {
                current.append("\n");
            }
            current.append(line);
        }
        addParagraphSegments(segments, current.toString());
        return segments.stream().filter(StringUtils::hasText).toList();
    }

    private void addParagraphSegments(List<String> segments, String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        for (String part : PARAGRAPH_SPLIT_PATTERN.split(normalized)) {
            String item = part.trim();
            if (StringUtils.hasText(item)) {
                segments.add(item);
            }
        }
    }

    private List<String> splitLargeSegment(String segment, int targetTokens, int overlapTokens) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = segment.length();
        while (start < length) {
            int end = findChunkEnd(segment, start, targetTokens);
            if (end <= start) {
                break;
            }
            String chunk = segment.substring(start, end).trim();
            if (StringUtils.hasText(chunk)) {
                chunks.add(chunk);
            }
            if (end >= length) {
                break;
            }
            int nextStart = findOverlapStart(segment, start, end, overlapTokens);
            start = Math.max(nextStart, start + 1);
        }
        return chunks;
    }

    private int findChunkEnd(String text, int start, int targetTokens) {
        int remaining = text.length() - start;
        if (remaining <= 0) {
            return start;
        }
        int low = start + 1;
        int high = Math.min(text.length(), start + Math.max(160, targetTokens * 6));
        while (high < text.length()
                && TokenCounter.estimateTokens(text.substring(start, high)) < targetTokens) {
            int nextHigh = Math.min(text.length(), high + Math.max(120, targetTokens * 3));
            if (nextHigh == high) {
                break;
            }
            high = nextHigh;
        }
        int best = low;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = TokenCounter.estimateTokens(text.substring(start, mid));
            if (tokens <= targetTokens) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return adjustBoundaryClean(text, start, best);
    }


    private int adjustBoundaryClean(String text, int start, int end) {
        int min = Math.min(text.length(), start + 40);
        int candidate = end;
        for (int i = end - 1; i >= min; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == ';') {
                candidate = i + 1;
                break;
            }
        }
        return Math.max(min, candidate);
    }

    private int findOverlapStart(String text, int chunkStart, int chunkEnd, int overlapTokens) {
        if (overlapTokens <= 0) {
            return chunkEnd;
        }
        int low = chunkStart;
        int high = chunkEnd;
        int best = chunkEnd;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = TokenCounter.estimateTokens(text.substring(mid, chunkEnd));
            if (tokens >= overlapTokens) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(chunkStart, Math.min(best, chunkEnd - 1));
    }

    private String trimToTokenBudget(String text, int maxTokens) {
        if (!StringUtils.hasText(text) || maxTokens <= 0) {
            return "";
        }
        if (TokenCounter.estimateTokens(text) <= maxTokens) {
            return text;
        }
        int low = 1;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = TokenCounter.estimateTokens(text.substring(0, mid));
            if (tokens <= maxTokens) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, Math.max(0, best)).trim();
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
        String normalized = normalizeQueryClean(query);
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



    private List<String> extractMustHaveKeywordsV2(String query, List<String> keywords) {
        List<String> mustHave = new ArrayList<>();
        if (!StringUtils.hasText(query)) {
            return mustHave;
        }
        String normalized = normalizeQueryClean(query);
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

    private String normalizeQueryClean(String query) {
        String trimmed = query == null ? "" : query.trim();
        trimmed = trimmed.replaceAll("[“”\"'‘’、，,。；;：:！？!?（）()【】\\[\\]<>《》]", " ");
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        String[] prefixes = {
                "介绍一个", "介绍一下", "介绍", "什么是", "请介绍", "讲一个", "讲讲", "说明一个", "解释一个", "讲解一个"
        };
        for (String prefix : prefixes) {
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length()).trim();
                break;
            }
        }
        return trimmed;
    }

    private List<String> filterStopwordsClean(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> stopwords = Set.of(
                "介绍", "介绍一个", "介绍一下", "讲一个", "讲讲", "说明", "说明一个", "解释", "解释一个",
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
        List<RagChunkCandidate> selectedCandidates = new ArrayList<>();
        List<Double> selectedScores = new ArrayList<>();
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
            selectedCandidates.add(best);
            selectedScores.add(bestQuerySim);
            selectedEmbeddings.add(best.getEmbedding());
            candidates.set(bestIdx, null);
        }
        if (!selectedCandidates.isEmpty()) {
            List<Long> ids = selectedCandidates.stream()
                    .map(RagChunkCandidate::getId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            java.util.Map<Long, String> contentMap = ragRepository.findChunkContentsByIds(userId, ids);
            for (int i = 0; i < selectedCandidates.size(); i++) {
                RagChunkCandidate c = selectedCandidates.get(i);
                String content = c.getId() == null ? null : contentMap.get(c.getId());
                selected.add(new RagChunkMatch(c.getDocId(), content, selectedScores.get(i)));
            }
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



