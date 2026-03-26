package com.harmony.backend.ai.rag.service.impl;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.embedding.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.model.PreparedRagChunk;
import com.harmony.backend.ai.rag.model.PreparedRagDocument;
import com.harmony.backend.ai.rag.model.RagChunkCandidate;
import com.harmony.backend.ai.rag.model.RagDocumentHit;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.ai.rag.model.RagEvidenceResult;
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.rag.service.support.RagIngestPipelineService;
import com.harmony.backend.ai.rag.service.support.RagMarkdownImageService;
import com.harmony.backend.ai.rag.service.support.RagRetrievalService;
import com.harmony.backend.common.exception.BusinessException;
import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.common.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnBean(name = "ragJdbcTemplate")
@RequiredArgsConstructor
@Slf4j
public class RagServiceImpl implements RagService {
    private static final Pattern PARAGRAPH_SPLIT_PATTERN = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern FILE_REFERENCE_PATTERN = Pattern.compile(
            "([\\p{IsAlphabetic}\\p{IsDigit}_\\-\\u4E00-\\u9FFF][\\p{IsAlphabetic}\\p{IsDigit}_\\-\\.\\u4E00-\\u9FFF]{0,120}\\.(?:md|markdown|txt|pdf|doc|docx|java|py|js|ts|json|yaml|yml|xml))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_REFERENCE_PATTERN = Pattern.compile("[\"“”'《【]([^\"“”'》】]{1,120})[\"“”'》】]");

    private static final String[] WHOLE_DOCUMENT_SUMMARY_TERMS = new String[] {
            "summary", "summarize", "overview", "whole document", "entire document", "whole file", "entire file",
            "\u603b\u7ed3", "\u6982\u62ec", "\u6458\u8981", "\u603b\u89c8", "\u6574\u4e2a\u6587\u4ef6", "\u6574\u4e2a\u6587\u6863", "\u5168\u6587", "\u6574\u7bc7"
    };
    private static final String[] WHOLE_DOCUMENT_FILE_HINTS = new String[] {
            "\u6587\u4ef6", "\u6587\u6863", "\u6587\u4ef6\u540d", "\u6587\u6863\u540d", "core content", "file", "document"
    };
    private static final String[] BROAD_OVERVIEW_TERMS = new String[] {
            "\u4ecb\u7ecd\u4e00\u4e0b", "\u4ecb\u7ecd", "\u8bb2\u8bb2", "\u8bf4\u8bf4", "\u662f\u4ec0\u4e48",
            "\u4ec0\u4e48\u662f", "\u539f\u7406", "\u4f5c\u7528", "\u6838\u5fc3\u6982\u5ff5", "\u57fa\u672c\u6982\u5ff5",
            "introduce", "overview", "what is", "explain", "basic concept", "core concept"
    };


    private final RagRepository ragRepository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;
    private final RagIngestPipelineService ragIngestPipelineService;
    private final RagMarkdownImageService ragMarkdownImageService;
    private final RagRetrievalService ragRetrievalService;

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
        PreparedRagDocument prepared = ragIngestPipelineService.prepare(content);
        return ingestPreparedDocument(userId, safeTitle, prepared);
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
        String enriched = ragMarkdownImageService.enrichMarkdownWithImageRefs(markdownContent, sourcePath);
        PreparedRagDocument prepared = ragIngestPipelineService.prepareMarkdown(enriched);
        return ingestPreparedDocument(userId, safeTitle, prepared);
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
        String enriched = ragMarkdownImageService.embedImages(markdownContent, images);
        PreparedRagDocument prepared = ragIngestPipelineService.prepareMarkdown(enriched);
        return ingestPreparedDocument(userId, safeTitle, prepared);
    }

    private String ingestPreparedDocument(Long userId, String safeTitle, PreparedRagDocument prepared) {
        if (prepared == null || !StringUtils.hasText(prepared.getContent())) {
            throw new BusinessException(400, "Content is empty after cleaning");
        }
        String contentHash = hashContent(prepared.getContent());
        String existingDocId = ragRepository.findActiveDocumentIdByHash(userId, contentHash);
        if (StringUtils.hasText(existingDocId)) {
            ragRepository.touchDocument(existingDocId, safeTitle);
            log.info("RAG dedup hit. userId={}, docId={}, title={}", userId, existingDocId, safeTitle);
            return existingDocId;
        }
        String docId = ragRepository.createDocument(userId, safeTitle, prepared.getContent(), contentHash);
        for (PreparedRagChunk chunk : prepared.getChunks()) {
            float[] embedding = embeddingService.embed(chunk.getContent());
            ragRepository.insertChunk(docId, userId, chunk.getContent(), embedding, serializeChunkMetadata(chunk));
        }
        return docId;
    }

    @Override
    public List<RagChunkMatch> search(Long userId, String query, Integer topK) {
        return ragRetrievalService.retrieve(userId, query, topK);
    }

    @Override
    public String buildContext(Long userId, String query, Integer topK) {
        return resolveEvidence(userId, query, topK).context();
    }

    @Override
    public RagEvidenceResult resolveEvidence(Long userId, String query, Integer topK) {
        List<RagChunkMatch> matches = search(userId, query, topK);
        RagEvidenceResult wholeDocumentEvidence = resolveWholeDocumentEvidence(userId, query, matches);
        if (wholeDocumentEvidence != null) {
            log.info("RAG whole-document summary hit. query='{}' matches={}", query, matches.size());
            return wholeDocumentEvidence;
        }
        if (matches.isEmpty()) {
            log.info("RAG retrieval miss. query='{}'", query);
            return RagEvidenceResult.empty();
        }
        log.info("RAG retrieval hit. query='{}' matches={}", query, matches.size());
        return new RagEvidenceResult(buildContextFromMatches(matches, properties.getSearch(), query), matches);
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


    private String buildContextFromMatches(List<RagChunkMatch> matches, RagProperties.Search search, String query) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        java.util.Map<String, Integer> docUsage = new java.util.HashMap<>();
        boolean broadOverviewQuery = isBroadOverviewQuery(query);
        int maxTokens = computeContextTokenBudget(broadOverviewQuery);
        int maxPerDoc = computeMaxChunksPerDocument(search, broadOverviewQuery);
        int usedTokens = 0;
        for (RagChunkMatch match : matches) {
            if (match == null || !StringUtils.hasText(match.getContent())) {
                continue;
            }
            String content = match.getContent().trim();
            String rendered = renderChunkForContext(match);
            if (seen.contains(rendered)) {
                continue;
            }
            String docId = match.getDocId() == null ? "" : match.getDocId();
            if (docUsage.getOrDefault(docId, 0) >= maxPerDoc) {
                continue;
            }
            seen.add(rendered);
            int contentTokens = TokenCounter.estimateTokens(rendered);
            if (contentTokens <= 0) {
                continue;
            }
            if (usedTokens >= maxTokens) {
                break;
            }
            String next = rendered;
            if (usedTokens + contentTokens > maxTokens) {
                next = trimToTokenBudget(rendered, maxTokens - usedTokens);
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
            docUsage.merge(docId, 1, Integer::sum);
        }
        return sb.toString().trim();
    }

    private boolean isBroadOverviewQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String lower = query.toLowerCase();
        if (containsAny(lower, WHOLE_DOCUMENT_SUMMARY_TERMS)) {
            return false;
        }
        return containsAny(lower, BROAD_OVERVIEW_TERMS);
    }

    private int computeContextTokenBudget(boolean broadOverviewQuery) {
        int baseBudget = Math.max(properties.getChunkTokenSize(), properties.getContextMaxTokens());
        if (!broadOverviewQuery) {
            return baseBudget;
        }
        return Math.max(baseBudget, properties.getChunkTokenSize() * 5);
    }

    private int computeMaxChunksPerDocument(RagProperties.Search search, boolean broadOverviewQuery) {
        int configured = search != null && search.getMaxChunksPerDocument() > 0
                ? search.getMaxChunksPerDocument()
                : 2;
        if (!broadOverviewQuery) {
            return configured;
        }
        return Math.max(configured, 4);
    }

    private RagEvidenceResult resolveWholeDocumentEvidence(Long userId,
                                                           String query,
                                                           List<RagChunkMatch> matches) {
        if (!isDocumentScopedOverviewQuery(query)) {
            return null;
        }
        RagDocumentHit documentHit = findReferencedDocument(userId, query);
        if (documentHit == null || !StringUtils.hasText(documentHit.getContent())) {
            return null;
        }
        String context = buildWholeDocumentContext(documentHit);
        if (!StringUtils.hasText(context)) {
            return null;
        }
        List<RagChunkMatch> evidenceMatches = (matches == null || matches.isEmpty())
                ? List.of(buildDocumentSummaryMatch(documentHit, context))
                : matches;
        return new RagEvidenceResult(context, evidenceMatches);
    }

    private boolean isDocumentScopedOverviewQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String lower = query.toLowerCase();
        boolean overviewIntent = containsAny(lower, WHOLE_DOCUMENT_SUMMARY_TERMS)
                || containsAny(lower, BROAD_OVERVIEW_TERMS);
        if (!overviewIntent) {
            return false;
        }
        return !extractDocumentReferences(query).isEmpty() || containsAny(lower, WHOLE_DOCUMENT_FILE_HINTS);
    }

    private RagDocumentHit findReferencedDocument(Long userId, String query) {
        if (userId == null || !StringUtils.hasText(query)) {
            return null;
        }
        for (String reference : extractDocumentReferences(query)) {
            List<RagDocumentHit> hits = ragRepository.searchDocumentsByTitle(userId, reference, 3);
            RagDocumentHit best = pickBestDocumentHit(reference, hits);
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private List<String> extractDocumentReferences(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        LinkedHashSet<String> references = new LinkedHashSet<>();
        Matcher fileMatcher = FILE_REFERENCE_PATTERN.matcher(query);
        while (fileMatcher.find()) {
            String value = fileMatcher.group(1);
            if (StringUtils.hasText(value)) {
                String cleaned = cleanDocumentReference(value);
                if (StringUtils.hasText(cleaned)) {
                    references.add(cleaned);
                }
            }
        }
        Matcher quotedMatcher = QUOTED_REFERENCE_PATTERN.matcher(query);
        while (quotedMatcher.find()) {
            String value = quotedMatcher.group(1);
            if (StringUtils.hasText(value)) {
                String cleaned = cleanDocumentReference(value);
                if (StringUtils.hasText(cleaned)) {
                    references.add(cleaned);
                }
            }
        }
        return new ArrayList<>(references);
    }

    private String cleanDocumentReference(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String cleaned = value.trim();
        String[] prefixes = {
                "请总结一下", "总结一下", "请总结", "总结", "概括一下", "概括", "介绍一下", "介绍", "讲讲", "说说",
                "please summarize", "summarize", "summary of", "overview of", "introduce"
        };
        boolean changed;
        do {
            changed = false;
            for (String prefix : prefixes) {
                if (cleaned.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    cleaned = cleaned.substring(prefix.length()).trim();
                    changed = true;
                }
            }
            cleaned = cleaned.replaceFirst("^[\\s:：,，;；]+", "").trim();
        } while (changed && StringUtils.hasText(cleaned));
        return cleaned;
    }

    private RagDocumentHit pickBestDocumentHit(String reference, List<RagDocumentHit> hits) {
        if (!StringUtils.hasText(reference) || hits == null || hits.isEmpty()) {
            return null;
        }
        String normalizedReference = normalizeText(reference).toLowerCase();
        RagDocumentHit fallback = null;
        for (RagDocumentHit hit : hits) {
            if (hit == null || !StringUtils.hasText(hit.getTitle())) {
                continue;
            }
            String normalizedTitle = normalizeText(hit.getTitle()).toLowerCase();
            if (normalizedTitle.equals(normalizedReference) || normalizedTitle.contains(normalizedReference)) {
                return hit;
            }
            if (fallback == null || hit.getScore() > fallback.getScore()) {
                fallback = hit;
            }
        }
        return fallback;
    }

    private String buildWholeDocumentContext(RagDocumentHit documentHit) {
        if (documentHit == null || !StringUtils.hasText(documentHit.getContent())) {
            return "";
        }
        String cleanContent = sanitizeContextNoise(documentHit.getContent()).trim();
        if (!StringUtils.hasText(cleanContent)) {
            return "";
        }
        String title = StringUtils.hasText(documentHit.getTitle()) ? documentHit.getTitle().trim() : "Document";
        String prefix = "[Document] " + title + "\n";
        int maxTokens = computeWholeDocumentTokenBudget();
        int prefixTokens = TokenCounter.estimateTokens(prefix);
        int remainingTokens = Math.max(0, maxTokens - prefixTokens);
        if (remainingTokens <= 0) {
            return trimToTokenBudget(prefix, maxTokens);
        }
        if (TokenCounter.estimateTokens(cleanContent) <= remainingTokens) {
            return prefix + cleanContent;
        }
        List<String> segments = expandDocumentSegments(cleanContent);
        if (segments.isEmpty()) {
            return prefix + trimToTokenBudget(cleanContent, remainingTokens);
        }
        String sampled = sampleDocumentSegments(segments, remainingTokens);
        if (!StringUtils.hasText(sampled)) {
            return prefix + trimToTokenBudget(cleanContent, remainingTokens);
        }
        return (prefix + sampled).trim();
    }

    private int computeWholeDocumentTokenBudget() {
        int baseBudget = Math.max(properties.getContextMaxTokens(), properties.getChunkTokenSize() * 2);
        return Math.min(6000, Math.max(baseBudget * 2, properties.getChunkTokenSize() * 6));
    }

    private List<String> expandDocumentSegments(String content) {
        List<String> rawSegments = splitIntoSegments(content);
        if (rawSegments.isEmpty()) {
            return List.of();
        }
        int targetTokens = Math.max(180, properties.getChunkTokenSize() / 2);
        int overlapTokens = Math.max(30, properties.getChunkTokenOverlap() / 2);
        List<String> expanded = new ArrayList<>();
        for (String segment : rawSegments) {
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            if (TokenCounter.estimateTokens(segment) > targetTokens * 13 / 10) {
                expanded.addAll(splitLargeSegment(segment, targetTokens, overlapTokens));
            } else {
                expanded.add(segment.trim());
            }
        }
        return expanded.stream().filter(StringUtils::hasText).toList();
    }

    private String sampleDocumentSegments(List<String> segments, int maxTokens) {
        if (segments == null || segments.isEmpty() || maxTokens <= 0) {
            return "";
        }
        int targetSegmentTokens = Math.max(140, properties.getChunkTokenSize() / 3);
        int targetCount = Math.max(3, Math.min(8, Math.max(1, maxTokens / targetSegmentTokens)));
        List<Integer> indexes = selectSegmentIndexes(segments.size(), targetCount);
        StringBuilder sb = new StringBuilder();
        int usedTokens = 0;
        for (int i = 0; i < indexes.size(); i++) {
            String segment = segments.get(indexes.get(i));
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            int remainingSegments = indexes.size() - i;
            int remainingBudget = maxTokens - usedTokens;
            if (remainingBudget <= 0) {
                break;
            }
            int perSegmentBudget = Math.max(80, remainingBudget / Math.max(1, remainingSegments));
            String excerpt = trimToTokenBudget(segment.trim(), perSegmentBudget);
            int excerptTokens = TokenCounter.estimateTokens(excerpt);
            if (!StringUtils.hasText(excerpt) || excerptTokens <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("[Excerpt ").append(i + 1).append("/").append(indexes.size()).append("]\n");
            sb.append(excerpt);
            usedTokens += excerptTokens;
        }
        return sb.toString().trim();
    }

    private List<Integer> selectSegmentIndexes(int size, int targetCount) {
        if (size <= 0 || targetCount <= 0) {
            return List.of();
        }
        if (size <= targetCount) {
            List<Integer> indexes = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                indexes.add(i);
            }
            return indexes;
        }
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        for (int i = 0; i < targetCount; i++) {
            double ratio = targetCount == 1 ? 0d : i / (double) (targetCount - 1);
            int index = (int) Math.round(ratio * (size - 1));
            selected.add(Math.max(0, Math.min(size - 1, index)));
        }
        return new ArrayList<>(selected);
    }

    private RagChunkMatch buildDocumentSummaryMatch(RagDocumentHit documentHit, String context) {
        try {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("title", documentHit.getTitle());
            metadata.put("headings", List.of("document_summary"));
            metadata.put("blockType", "document");
            return new RagChunkMatch(
                    documentHit.getDocId(),
                    trimToTokenBudget(context, Math.max(180, properties.getSnippetMaxTokens())),
                    Math.max(0.9d, documentHit.getScore()),
                    objectMapper.writeValueAsString(metadata)
            );
        } catch (Exception e) {
            return new RagChunkMatch(
                    documentHit.getDocId(),
                    trimToTokenBudget(context, Math.max(180, properties.getSnippetMaxTokens())),
                    Math.max(0.9d, documentHit.getScore()),
                    "{}"
            );
        }
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
            if (c == '\n' || c == '\u3002' || c == '\uff1b' || c == '\uff1a' || c == '.' || c == '!' || c == '?' || c == ';') {
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

    private String normalizeQueryClean(String query) {
        String trimmed = query == null ? "" : query.trim();
        trimmed = trimmed.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4E00-\\u9FFF]+", " ");
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        String[] prefixes = {
                "\u4ecb\u7ecd\u4e00\u4e0b", "\u4ecb\u7ecd\u4e00\u4e2a", "\u4ecb\u7ecd", "\u4ec0\u4e48\u662f", "\u8bf7\u4ecb\u7ecd", "\u8bb2\u4e00\u4e0b", "\u8bb2\u8bb2", "\u8bf4\u660e\u4e00\u4e0b", "\u89e3\u91ca\u4e00\u4e0b", "\u8bf7\u89e3\u91ca",
                "\u8bf7\u603b\u7ed3", "\u603b\u7ed3\u4e00\u4e0b", "\u603b\u7ed3", "\u6982\u62ec\u4e00\u4e0b", "\u6982\u62ec", "\u6839\u636e\u6587\u4ef6\u540d", "\u6839\u636e\u6587\u6863\u540d"
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
                "\u4ecb\u7ecd", "\u4ecb\u7ecd\u4e00\u4e0b", "\u4ecb\u7ecd\u4e00\u4e2a", "\u8bb2\u4e00\u4e0b", "\u8bb2\u8bb2", "\u8bf4\u660e", "\u8bf4\u660e\u4e00\u4e0b", "\u89e3\u91ca", "\u89e3\u91ca\u4e00\u4e0b",
                "\u5982\u4f55", "\u4ec0\u4e48", "\u4ec0\u4e48\u662f", "\u662f\u5426", "\u53ef\u4ee5", "\u9700\u8981", "\u5e94\u8be5", "\u6709\u6ca1\u6709", "\u600e\u4e48",
                "\u54ea\u4e2a", "\u54ea\u4e9b", "\u4e3b\u8981", "\u5185\u5bb9", "\u4fe1\u606f", "\u76f8\u5173", "\u95ee\u9898", "\u5b58\u5728", "\u7cfb\u7edf",
                "\u8bf7", "\u603b\u7ed3", "\u603b\u7ed3\u4e00\u4e0b", "\u6982\u62ec", "\u6982\u62ec\u4e00\u4e0b", "\u6587\u4ef6\u540d", "\u6587\u6863\u540d", "\u6838\u5fc3"
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


    private String serializeChunkMetadata(PreparedRagChunk chunk) {
        try {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("blockType", chunk.getBlockType());
            metadata.put("headings", chunk.getHeadings());
            metadata.put("ordinal", chunk.getOrdinal());
            metadata.put("tokenCount", chunk.getTokenCount());
            if (chunk.getAttributes() != null && !chunk.getAttributes().isEmpty()) {
                metadata.putAll(chunk.getAttributes());
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String renderChunkForContext(RagChunkMatch match) {
        if (match == null || !StringUtils.hasText(match.getContent())) {
            return "";
        }
        String content = sanitizeContextNoise(match.getContent()).trim();
        if (!StringUtils.hasText(content)) {
            return "";
        }
        java.util.Map<String, Object> metadata = parseChunkMetadata(match.getChunkMetadata());
        Object headingsObj = metadata.get("headings");
        if (headingsObj instanceof List<?> headings && !headings.isEmpty()) {
            String section = headings.stream().map(String::valueOf).filter(StringUtils::hasText).reduce((a, b) -> a + " > " + b).orElse("");
            if (StringUtils.hasText(section) && !content.startsWith("[Section]")) {
                return "[Section] " + section + "\n" + content;
            }
        }
        return content;
    }

    private String sanitizeContextNoise(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String sanitized = content
                .replaceAll("!\\[\\[[^\\]]+\\]\\]", " ")
                .replaceAll("!\\[[^\\]]*\\]\\([^\\)]+\\)", " ")
                .replaceAll("\\[\\[Pasted image[^\\]]*\\]\\]", " ")
                .replaceAll("(?im)^\\s*Pasted image[^\\r\\n]*$", " ")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n");
        return sanitized.trim();
    }

    private java.util.Map<String, Object> parseChunkMetadata(String chunkMetadata) {
        if (!StringUtils.hasText(chunkMetadata)) {
            return java.util.Map.of();
        }
        try {
            return objectMapper.readValue(chunkMetadata, new TypeReference<java.util.Map<String, Object>>() {});
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    private String metadataString(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer metadataInt(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> metadataStringList(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Hash content failed", e);
        }
    }

    private boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text) || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (StringUtils.hasText(needle) && text.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

}



