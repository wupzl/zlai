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
import com.harmony.backend.ai.rag.model.RagDocumentSummary;
import com.harmony.backend.ai.rag.repository.RagRepository;
import com.harmony.backend.ai.rag.service.OcrService;
import com.harmony.backend.ai.rag.service.RagService;
import com.harmony.backend.ai.rag.service.support.RagIngestPipelineService;
import com.harmony.backend.ai.rag.service.support.RagOcrOptimizer;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;
    private final OcrService ocrService;
    private final RagIngestPipelineService ragIngestPipelineService;
    private final RagOcrOptimizer ragOcrOptimizer;

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
        String enriched = enrichMarkdownWithImageRefs(markdownContent, sourcePath);
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
        String enriched = embedImages(markdownContent, images);
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
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(400, "Query is required");
        }
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        RagProperties.Search search = properties.getSearch();
        if (search != null && search.isHybridEnabled()) {
            return hybridSearch(userId, query, limit, search);
        }
        return vectorSearch(userId, query, limit, search);
    }

    private List<RagChunkMatch> vectorSearch(Long userId,
                                             String query,
                                             int limit,
                                             RagProperties.Search search) {
        float[] embedding = embeddingService.embed(query);
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
        boolean preferWholeDocument = shouldPreferWholeDocument(query);
        String documentAwareContext = resolveWholeDocumentContext(userId, query);
        if (StringUtils.hasText(documentAwareContext)) {
            log.info("RAG document-aware hit. query='{}'", query);
            return documentAwareContext;
        }
        if (preferWholeDocument) {
            log.info("RAG document-aware preferred but no document candidate. query='{}'", query);
        }
        String sectionAwareContext = preferWholeDocument ? "" : resolveSectionAwareContext(userId, query, topK);
        if (StringUtils.hasText(sectionAwareContext)) {
            log.info("RAG section-aware hit. query='{}'", query);
            return sectionAwareContext;
        }
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
        return buildContextFromMatches(matches, properties.getSearch());
    }

    private String resolveSectionAwareContext(Long userId, String query, Integer topK) {
        List<String> sectionTargets = extractSectionTargets(query);
        if (sectionTargets.isEmpty()) {
            return "";
        }
        log.info("RAG section-aware targets. query='{}' sectionTargets={}", query, sectionTargets);
        List<RagDocumentHit> docCandidates = findSectionDocumentCandidates(userId, query, sectionTargets);
        if (docCandidates.isEmpty()) {
            log.info("RAG section-aware doc miss. query='{}'", query);
            return "";
        }
        RagDocumentHit bestDoc = docCandidates.get(0);
        log.info("RAG section-aware doc candidates. query='{}' candidates={}", query, summarizeDocumentHits(docCandidates, 3));
        int limit = topK != null && topK > 0 ? topK : properties.getDefaultTopK();
        java.util.Map<String, RagChunkMatch> merged = new java.util.LinkedHashMap<>();
        for (String sectionTarget : sectionTargets) {
            List<RagChunkMatch> headingHits =
                    ragRepository.searchChunkMatchesByHeadingInDocument(userId, bestDoc.getDocId(), sectionTarget, Math.max(limit * 2, limit));
            log.info("RAG section-aware heading hits. query='{}' doc='{}' target='{}' hits={}",
                    query, bestDoc.getTitle(), sectionTarget, summarizeChunkMatches(headingHits, 4));
            mergeKeywordCandidates(query, merged, headingHits, 0.46);
            for (String keyword : extractKeywords(sectionTarget)) {
                List<RagChunkMatch> keywordHits =
                        ragRepository.searchChunkMatchesByKeywordInDocument(userId, bestDoc.getDocId(), keyword, Math.max(limit, 4));
                mergeKeywordCandidates(query, merged, keywordHits, 0.08);
            }
        }
        if (merged.isEmpty()) {
            log.info("RAG section-aware chunk miss. query='{}' doc='{}'", query, bestDoc.getTitle());
            return "";
        }
        List<RagChunkMatch> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(RagChunkMatch::getScore).reversed());
        if (ranked.size() > limit) {
            ranked = new ArrayList<>(ranked.subList(0, limit));
        }
        log.info("RAG section-aware ranked chunks. query='{}' doc='{}' chunks={}",
                query, bestDoc.getTitle(), summarizeChunkMatches(ranked, Math.min(4, ranked.size())));
        return buildContextFromMatches(ranked, properties.getSearch());
    }

    private String resolveWholeDocumentContext(Long userId, String query) {
        if (!isWholeDocumentIntent(query)) {
            return "";
        }
        List<RagDocumentHit> candidates = findWholeDocumentCandidates(userId, query);
        if (candidates.isEmpty()) {
            return "";
        }
        RagDocumentHit best = candidates.get(0);
        return buildWholeDocumentContext(best, query);
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

    private List<RagDocumentHit> findDocumentCandidates(Long userId, String query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (StringUtils.hasText(query)) {
            terms.add(query.trim());
        }
        terms.addAll(extractKeywords(query));
        java.util.Map<String, RagDocumentHit> merged = new java.util.LinkedHashMap<>();
        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }
            List<RagDocumentHit> hits = ragRepository.searchDocumentsByTitle(userId, term, 5);
            for (RagDocumentHit hit : hits) {
                if (hit == null || !StringUtils.hasText(hit.getDocId()) || !StringUtils.hasText(hit.getContent())) {
                    continue;
                }
                double score = documentTitleScore(query, hit);
                RagDocumentHit existing = merged.get(hit.getDocId());
                if (existing == null || score > existing.getScore()) {
                    merged.put(hit.getDocId(), new RagDocumentHit(hit.getDocId(), hit.getTitle(), hit.getContent(), score));
                }
            }
        }
        List<RagDocumentHit> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(RagDocumentHit::getScore).reversed());
        return ranked;
    }

    private List<RagDocumentHit> findSectionDocumentCandidates(Long userId, String query, List<String> sectionTargets) {
        List<String> docTitleTargets = extractDocumentTitleTargets(query, sectionTargets);
        java.util.Map<String, RagDocumentHit> merged = new java.util.LinkedHashMap<>();
        for (String titleTarget : docTitleTargets) {
            List<RagDocumentHit> hits = ragRepository.searchDocumentsByTitle(userId, titleTarget, 5);
            for (RagDocumentHit hit : hits) {
                if (hit == null || !StringUtils.hasText(hit.getDocId()) || !StringUtils.hasText(hit.getContent())) {
                    continue;
                }
                double score = documentTitleScore(titleTarget, hit) + 0.28;
                RagDocumentHit existing = merged.get(hit.getDocId());
                if (existing == null || score > existing.getScore()) {
                    merged.put(hit.getDocId(), new RagDocumentHit(hit.getDocId(), hit.getTitle(), hit.getContent(), score));
                }
            }
        }
        if (merged.isEmpty()) {
            return findDocumentCandidates(userId, query);
        }
        List<RagDocumentHit> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(RagDocumentHit::getScore).reversed());
        return ranked;
    }

    private List<RagChunkMatch> hybridSearch(Long userId,
                                             String query,
                                             int limit,
                                             RagProperties.Search search) {
        List<RagChunkMatch> vectorMatches = vectorSearch(userId, query, Math.max(limit * 2, limit), search);
        List<RagChunkMatch> keywordMatches = keywordSearch(userId, query, Math.max(limit * 2, limit));
        List<RagChunkMatch> merged = mergeHybridMatches(query, vectorMatches, keywordMatches, search);
        if (merged.size() > limit) {
            return new ArrayList<>(merged.subList(0, limit));
        }
        return merged;
    }

    private List<RagChunkMatch> keywordSearch(Long userId, String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String normalized = query.trim();
        terms.add(normalized);
        terms.addAll(extractKeywords(query));
        List<String> sectionTargets = extractSectionTargets(query);
        java.util.Map<String, RagChunkMatch> merged = new java.util.LinkedHashMap<>();
        for (String target : sectionTargets) {
            if (!StringUtils.hasText(target)) {
                continue;
            }
            List<RagChunkMatch> headingHits = ragRepository.searchChunkMatchesByHeading(userId, target, limit);
            mergeKeywordCandidates(query, merged, headingHits, 0.28);
        }
        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }
            List<RagChunkMatch> hits = ragRepository.searchChunkMatchesByKeyword(userId, term, limit);
            mergeKeywordCandidates(query, merged, hits, 0.0);
        }
        List<RagChunkMatch> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(RagChunkMatch::getScore).reversed());
        if (ranked.size() > limit) {
            return new ArrayList<>(ranked.subList(0, limit));
        }
        return ranked;
    }

    private List<RagChunkMatch> mergeHybridMatches(String query,
                                                   List<RagChunkMatch> vectorMatches,
                                                   List<RagChunkMatch> keywordMatches,
                                                   RagProperties.Search search) {
        double vectorWeight = search == null ? 0.65 : clamp(search.getVectorWeight(), 0.0, 1.0);
        double keywordWeight = search == null ? 0.35 : clamp(search.getKeywordWeight(), 0.0, 1.0);
        if (vectorWeight == 0.0 && keywordWeight == 0.0) {
            vectorWeight = 0.65;
            keywordWeight = 0.35;
        }
        java.util.Map<String, Double> vectorScoreMap = new java.util.HashMap<>();
        java.util.Map<String, RagChunkMatch> contentMap = new java.util.LinkedHashMap<>();
        if (vectorMatches != null) {
            for (RagChunkMatch match : vectorMatches) {
                if (match == null || !StringUtils.hasText(match.getContent())) {
                    continue;
                }
                String key = normalizeText(match.getContent());
                vectorScoreMap.merge(key, match.getScore(), Math::max);
                contentMap.putIfAbsent(key, match);
            }
        }
        java.util.Map<String, Double> keywordScoreMap = new java.util.HashMap<>();
        if (keywordMatches != null) {
            for (RagChunkMatch match : keywordMatches) {
                if (match == null || !StringUtils.hasText(match.getContent())) {
                    continue;
                }
                String key = normalizeText(match.getContent());
                keywordScoreMap.merge(key, match.getScore(), Math::max);
                contentMap.putIfAbsent(key, match);
            }
        }
        List<RagChunkMatch> merged = new ArrayList<>();
        for (java.util.Map.Entry<String, RagChunkMatch> entry : contentMap.entrySet()) {
            String key = entry.getKey();
            RagChunkMatch base = entry.getValue();
            double vectorScore = vectorScoreMap.getOrDefault(key, 0.0);
            double keywordScore = keywordScoreMap.getOrDefault(key, 0.0);
            double rerank = rerankScore(query, base.getContent(), vectorScore, keywordScore, base.getChunkMetadata());
            double finalScore = vectorScore * vectorWeight + keywordScore * keywordWeight + rerank;
            merged.add(new RagChunkMatch(base.getDocId(), base.getContent(), finalScore, base.getChunkMetadata()));
        }
        merged.sort(Comparator.comparingDouble(RagChunkMatch::getScore).reversed());
        return merged;
    }

    private double lexicalScore(String query, String content, double baseScore) {
        double score = Math.max(0.0, baseScore);
        if (!StringUtils.hasText(content)) {
            return score;
        }
        String normalizedQuery = normalizeText(query);
        String normalizedContent = normalizeText(content);
        if (StringUtils.hasText(normalizedQuery) && normalizedContent.contains(normalizedQuery)) {
            score += 0.25;
        }
        List<String> keywords = extractKeywords(query);
        int hits = 0;
        for (String keyword : keywords) {
            if (containsIgnoreCase(content, keyword)) {
                hits++;
            }
        }
        if (!keywords.isEmpty()) {
            score += Math.min(0.3, hits / (double) keywords.size() * 0.3);
        }
        return score;
    }

    private double rerankScore(String query,
                               String content,
                               double vectorScore,
                               double keywordScore,
                               String chunkMetadata) {
        if (!StringUtils.hasText(content)) {
            return 0.0;
        }
        double score = 0.0;
        String normalizedQuery = normalizeText(query);
        String normalizedContent = normalizeText(content);
        java.util.Map<String, Object> metadata = parseChunkMetadata(chunkMetadata);
        String blockType = metadataString(metadata, "blockType");
        List<String> headings = metadataStringList(metadata, "headings");
        Integer ordinal = metadataInt(metadata, "ordinal");
        List<String> sectionTargets = extractSectionTargets(query);
        if (StringUtils.hasText(normalizedQuery) && normalizedContent.contains(normalizedQuery)) {
            score += 0.22;
        }
        List<String> mustHave = extractMustHaveKeywordsV2(query, extractKeywords(query));
        if (!mustHave.isEmpty()) {
            boolean hit = false;
            for (String token : mustHave) {
                if (containsIgnoreCase(content, token)) {
                    hit = true;
                    break;
                }
            }
            score += hit ? 0.14 : -0.08;
        }
        if (content.contains("[Section]")) {
            score += 0.05;
        }
        if (!sectionTargets.isEmpty()) {
            boolean exactSectionHit = false;
            boolean partialSectionHit = false;
            for (String heading : headings) {
                for (String target : sectionTargets) {
                    if (sectionHeadingExactMatch(heading, target)) {
                        exactSectionHit = true;
                        break;
                    }
                    if (sectionHeadingPartialMatch(heading, target)) {
                        partialSectionHit = true;
                    }
                }
                if (exactSectionHit) {
                    break;
                }
            }
            if (exactSectionHit) {
                score += 0.34;
            } else if (partialSectionHit) {
                score += 0.12;
            } else {
                score -= 0.06;
            }
        }
        if (!headings.isEmpty()) {
            score += 0.05;
            int headingHits = 0;
            for (String heading : headings) {
                if (containsIgnoreCase(heading, query)) {
                    headingHits++;
                } else {
                    for (String keyword : extractKeywords(query)) {
                        if (containsIgnoreCase(heading, keyword)) {
                            headingHits++;
                            break;
                        }
                    }
                }
            }
            score += Math.min(0.12, headingHits * 0.04);
        }
        if ("heading".equalsIgnoreCase(blockType)) {
            score += 0.12;
        } else if ("table".equalsIgnoreCase(blockType)) {
            score += 0.07;
        } else if ("list".equalsIgnoreCase(blockType)) {
            score += 0.04;
        } else if ("mixed".equalsIgnoreCase(blockType)) {
            score -= 0.02;
        }
        int tokens = TokenCounter.estimateTokens(content);
        if (tokens > 0 && tokens < Math.max(80, properties.getChunkTokenSize() * 2)) {
            score += 0.03;
        }
        if (ordinal != null && ordinal > 12) {
            score -= 0.01;
        }
        if (vectorScore > 0.0 && keywordScore > 0.0) {
            score += 0.08;
        }
        return score;
    }

    private void mergeKeywordCandidates(String query,
                                        java.util.Map<String, RagChunkMatch> merged,
                                        List<RagChunkMatch> hits,
                                        double extraBoost) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (RagChunkMatch hit : hits) {
            if (hit == null || !StringUtils.hasText(hit.getContent())) {
                continue;
            }
            String key = normalizeText(hit.getContent());
            double lexicalScore = lexicalScore(query, hit.getContent(), hit.getScore()) + extraBoost;
            RagChunkMatch existing = merged.get(key);
            if (existing == null || lexicalScore > existing.getScore()) {
                merged.put(key, new RagChunkMatch(hit.getDocId(), hit.getContent(), lexicalScore, hit.getChunkMetadata()));
            }
        }
    }

    private List<String> extractSectionTargets(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String token : extractQuotedSegments(query)) {
            String normalized = normalizeSectionTarget(token);
            if (StringUtils.hasText(normalized) && looksLikeSectionTarget(normalized)) {
                targets.add(normalized);
            }
        }
        Matcher numberedMatcher = Pattern.compile("((?:[0-9]+(?:\\.[0-9]+)*\\)?)[^\\n,.;:!?]{1,120})").matcher(query);
        while (numberedMatcher.find()) {
            String token = normalizeSectionTarget(numberedMatcher.group(1));
            if (StringUtils.hasText(token) && looksLikeSectionTarget(token)) {
                targets.add(token);
            }
        }
        return new ArrayList<>(targets);
    }

    private String normalizeSectionTarget(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
                .replace('`', ' ')
                .replace('\u300a', ' ')
                .replace('\u300b', ' ')
                .replace('"', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> extractDocumentTitleTargets(String query, List<String> sectionTargets) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Set<String> sectionSet = new LinkedHashSet<>();
        if (sectionTargets != null) {
            for (String sectionTarget : sectionTargets) {
                String normalized = normalizeSectionTarget(sectionTarget);
                if (StringUtils.hasText(normalized)) {
                    sectionSet.add(normalized);
                }
            }
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String token : extractQuotedSegments(query)) {
            String normalized = normalizeSectionTarget(token);
            if (!StringUtils.hasText(normalized) || sectionSet.contains(normalized) || looksLikeSectionTarget(normalized)) {
                continue;
            }
            targets.add(normalized);
        }
        return new ArrayList<>(targets);
    }

    private List<String> extractQuotedSegments(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        LinkedHashSet<String> segments = new LinkedHashSet<>();
        collectQuotedSegments(text, segments, "\u300a", "\u300b");
        collectQuotedSegments(text, segments, "\"", "\"");
        collectQuotedSegments(text, segments, "\u201c", "\u201d");
        return new ArrayList<>(segments);
    }

    private void collectQuotedSegments(String text, Set<String> segments, String open, String close) {
        if (!StringUtils.hasText(text) || segments == null || !StringUtils.hasText(open) || !StringUtils.hasText(close)) {
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int openIdx = text.indexOf(open, start);
            if (openIdx < 0) {
                break;
            }
            int closeIdx = text.indexOf(close, openIdx + open.length());
            if (closeIdx < 0) {
                break;
            }
            String candidate = text.substring(openIdx + open.length(), closeIdx).trim();
            if (candidate.length() >= 2 && candidate.length() <= 160) {
                segments.add(candidate);
            }
            start = closeIdx + close.length();
        }
    }

    private List<String> summarizeDocumentHits(List<RagDocumentHit> hits, int limit) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .limit(Math.max(1, limit))
                .map(hit -> String.format("%s(score=%.3f)", hit.getTitle(), hit.getScore()))
                .toList();
    }

    private List<String> summarizeChunkMatches(List<RagChunkMatch> hits, int limit) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .limit(Math.max(1, limit))
                .map(hit -> {
                    java.util.Map<String, Object> metadata = parseChunkMetadata(hit.getChunkMetadata());
                    List<String> headings = metadataStringList(metadata, "headings");
                    String headingText = headings.isEmpty() ? "-" : String.join(" > ", headings);
                    return String.format("%s(score=%.3f, heading=%s)",
                            hit.getDocId(), hit.getScore(), headingText);
                })
                .toList();
    }

    private boolean looksLikeSectionTarget(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = normalizeText(value);
        return value.matches(".*\\d+(?:\\.\\d+)*.*")
                || normalized.contains("\u8ba4\u8bc1")
                || normalized.contains("\u8fd4\u56de\u7ed3\u6784")
                || normalized.contains("\u603b\u89c8")
                || normalized.contains("\u5165\u5e93\u6d41\u7a0b")
                || normalized.contains("chunksize")
                || normalized.contains("prompt context");
    }

    private boolean sectionHeadingExactMatch(String heading, String target) {
        String normalizedHeading = normalizeSectionTarget(heading);
        String normalizedTarget = normalizeSectionTarget(target);
        if (!StringUtils.hasText(normalizedHeading) || !StringUtils.hasText(normalizedTarget)) {
            return false;
        }
        return normalizeText(normalizedHeading).contains(normalizeText(normalizedTarget))
                || normalizeText(normalizedTarget).contains(normalizeText(normalizedHeading));
    }

    private boolean sectionHeadingPartialMatch(String heading, String target) {
        if (!StringUtils.hasText(heading) || !StringUtils.hasText(target)) {
            return false;
        }
        List<String> keywords = extractKeywords(target);
        if (keywords.isEmpty()) {
            return false;
        }
        int hits = 0;
        for (String keyword : keywords) {
            if (containsIgnoreCase(heading, keyword)) {
                hits++;
            }
        }
        return hits >= Math.max(1, Math.min(2, keywords.size() / 2));
    }

    private double documentTitleScore(String query, RagDocumentHit hit) {
        if (hit == null) {
            return 0.0;
        }
        double score = Math.max(0.0, hit.getScore());
        String title = hit.getTitle() == null ? "" : hit.getTitle();
        String normalizedQuery = normalizeText(query);
        String normalizedTitle = normalizeText(title);
        if (StringUtils.hasText(normalizedQuery) && normalizedTitle.contains(normalizedQuery)) {
            score += 0.45;
        }
        List<String> keywords = extractKeywords(query);
        int hits = 0;
        for (String keyword : keywords) {
            if (containsIgnoreCase(title, keyword)) {
                hits++;
            }
        }
        if (!keywords.isEmpty()) {
            score += Math.min(0.35, hits / (double) keywords.size() * 0.35);
        }
        if (title.contains(".pdf") || title.contains(".doc") || title.contains(".docx") || title.contains(".txt") || title.contains(".md")) {
            score += 0.04;
        }
        return score;
    }

    private boolean isWholeDocumentIntent(String query) {
        return shouldPreferWholeDocument(query);
    }

    private boolean shouldPreferWholeDocument(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String lower = query.toLowerCase();
        boolean hasSummaryVerb = containsAny(lower,
                "summary", "summarize", "overview", "whole document", "entire document", "whole file", "entire file",
                "总结", "概括", "摘要", "总览", "整个文件", "整个文档", "全文", "整篇");
        boolean hasFileHint = containsAny(lower,
                "文件", "文档", "文件名", "文档名", "core content", "file");
        boolean hasQuotedTitle = !extractQuotedSegments(query).isEmpty();
        return hasSummaryVerb || (hasQuotedTitle && hasFileHint);
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

    private List<RagDocumentHit> findWholeDocumentCandidates(Long userId, String query) {
        List<String> titleTargets = extractDocumentTitleTargets(query, List.of());
        if (titleTargets.isEmpty()) {
            return findDocumentCandidates(userId, query);
        }
        java.util.Map<String, RagDocumentHit> merged = new java.util.LinkedHashMap<>();
        for (String titleTarget : titleTargets) {
            List<RagDocumentHit> hits = ragRepository.searchDocumentsByTitle(userId, titleTarget, 5);
            for (RagDocumentHit hit : hits) {
                if (hit == null || !StringUtils.hasText(hit.getDocId()) || !StringUtils.hasText(hit.getContent())) {
                    continue;
                }
                double score = documentTitleScore(titleTarget, hit) + 0.35;
                RagDocumentHit existing = merged.get(hit.getDocId());
                if (existing == null || score > existing.getScore()) {
                    merged.put(hit.getDocId(), new RagDocumentHit(hit.getDocId(), hit.getTitle(), hit.getContent(), score));
                }
            }
        }
        if (merged.isEmpty()) {
            return findDocumentCandidates(userId, query);
        }
        List<RagDocumentHit> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble(RagDocumentHit::getScore).reversed());
        return ranked;
    }

    private String buildWholeDocumentContext(RagDocumentHit hit, String query) {
        if (hit == null || !StringUtils.hasText(hit.getContent())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[Document] ").append(hit.getTitle() == null ? "Untitled" : hit.getTitle()).append("\n");
        String outline = extractDocumentOutline(hit.getContent());
        if (StringUtils.hasText(outline)) {
            sb.append("[Outline]\n").append(outline).append("\n");
        }
        int budget = Math.max(properties.getContextMaxTokens(), properties.getChunkTokenSize() * 3);
        int used = TokenCounter.estimateTokens(sb.toString());
        List<String> selected = selectDocumentParagraphs(hit.getContent(), query, budget - used);
        for (String paragraph : selected) {
            if (!StringUtils.hasText(paragraph)) {
                continue;
            }
            int tokens = TokenCounter.estimateTokens(paragraph);
            if (tokens <= 0 || used + tokens > budget) {
                continue;
            }
            sb.append("\n").append(paragraph.trim()).append("\n");
            used += tokens;
            if (used >= budget) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private String extractDocumentOutline(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.matches("^#{1,6}\\s+.*") || trimmed.startsWith("[Section]")) {
                lines.add(trimmed.replaceFirst("^#+\\s*", ""));
            }
            if (lines.size() >= 8) {
                break;
            }
        }
        return String.join("\n", lines);
    }

    private List<String> selectDocumentParagraphs(String content, String query, int tokenBudget) {
        if (!StringUtils.hasText(content) || tokenBudget <= 0) {
            return List.of();
        }
        List<SectionParagraph> paragraphs = splitDocumentParagraphs(content);
        if (paragraphs.isEmpty()) {
            return List.of(trimToTokenBudget(content, tokenBudget));
        }
        List<ParagraphCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            SectionParagraph paragraph = paragraphs.get(i);
            if (paragraph == null || !StringUtils.hasText(paragraph.text())) {
                continue;
            }
            double score = paragraphScore(query, paragraph.text(), paragraph.section(), i, paragraphs.size());
            candidates.add(new ParagraphCandidate(paragraph.text(), score, i, paragraph.section()));
        }
        candidates.sort(Comparator.comparingDouble(ParagraphCandidate::score).reversed());
        List<ParagraphCandidate> selected = new ArrayList<>();
        selected.addAll(candidates.stream().filter(c -> c.index() == 0).limit(1).toList());
        java.util.Set<String> coveredSections = new java.util.LinkedHashSet<>();
        for (ParagraphCandidate candidate : selected) {
            if (StringUtils.hasText(candidate.section())) {
                coveredSections.add(candidate.section());
            }
        }
        for (ParagraphCandidate candidate : candidates) {
            if (selected.stream().anyMatch(existing -> existing.index() == candidate.index())) {
                continue;
            }
            if (!StringUtils.hasText(candidate.section()) || coveredSections.contains(candidate.section())) {
                continue;
            }
            selected.add(candidate);
            coveredSections.add(candidate.section());
            if (selected.size() >= 6) {
                break;
            }
        }
        for (ParagraphCandidate candidate : candidates) {
            if (selected.stream().anyMatch(existing -> existing.index() == candidate.index())) {
                continue;
            }
            long sameSectionCount = selected.stream()
                    .filter(existing -> java.util.Objects.equals(existing.section(), candidate.section()))
                    .count();
            if (sameSectionCount >= 2) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() >= 6) {
                break;
            }
        }
        selected.sort(Comparator.comparingInt(ParagraphCandidate::index));
        List<String> result = new ArrayList<>();
        int used = 0;
        for (ParagraphCandidate candidate : selected) {
            String trimmed = trimToTokenBudget(candidate.text(), tokenBudget - used);
            int tokens = TokenCounter.estimateTokens(trimmed);
            if (!StringUtils.hasText(trimmed) || tokens <= 0) {
                continue;
            }
            result.add(trimmed);
            used += tokens;
            if (used >= tokenBudget) {
                break;
            }
        }
        return result;
    }

    private List<SectionParagraph> splitDocumentParagraphs(String content) {
        List<SectionParagraph> parts = new ArrayList<>();
        String currentSection = "";
        for (String part : content.split("\\n\\s*\\n")) {
            String trimmed = part == null ? "" : part.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("[Section]")) {
                int lineBreak = trimmed.indexOf('\n');
                String header = lineBreak > 0 ? trimmed.substring(0, lineBreak).trim() : trimmed;
                currentSection = header.replaceFirst("^\\[Section]\\s*", "").trim();
            } else if (trimmed.matches("^#{1,6}\\s+.*")) {
                currentSection = trimmed.replaceFirst("^#{1,6}\\s*", "").trim();
            }
            parts.add(new SectionParagraph(trimmed, currentSection));
        }
        return parts;
    }

    private double paragraphScore(String query, String paragraph, String section, int index, int total) {
        double score = 0.0;
        String normalizedQuery = normalizeText(query);
        String normalizedParagraph = normalizeText(paragraph);
        if (StringUtils.hasText(normalizedQuery) && normalizedParagraph.contains(normalizedQuery)) {
            score += 0.5;
        }
        List<String> keywords = extractKeywords(query);
        int hits = 0;
        for (String keyword : keywords) {
            if (containsIgnoreCase(paragraph, keyword)) {
                hits++;
            }
        }
        if (!keywords.isEmpty()) {
            score += Math.min(0.35, hits / (double) keywords.size() * 0.35);
        }
        if (index == 0) {
            score += 0.22;
        }
        if (total > 1 && index == total - 1) {
            score += 0.12;
        }
        if (paragraph.startsWith("#") || paragraph.startsWith("[Section]")) {
            score += 0.08;
        }
        if (StringUtils.hasText(section)) {
            if (containsIgnoreCase(section, query)) {
                score += 0.18;
            } else {
                int sectionHits = 0;
                for (String keyword : extractKeywords(query)) {
                    if (containsIgnoreCase(section, keyword)) {
                        sectionHits++;
                    }
                }
                score += Math.min(0.12, sectionHits * 0.04);
            }
        }
        return score;
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

    private String buildContextFromMatches(List<RagChunkMatch> matches, RagProperties.Search search) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        java.util.Map<String, Integer> docUsage = new java.util.HashMap<>();
        int maxTokens = Math.max(properties.getChunkTokenSize(), properties.getContextMaxTokens());
        int maxPerDoc = search != null && search.getMaxChunksPerDocument() > 0
                ? search.getMaxChunksPerDocument()
                : 2;
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
            if (!ragOcrOptimizer.shouldProcessImage(name, bytes)) {
                continue;
            }
            String ocrText = ragOcrOptimizer.cleanOcrText(ocrService.extractText(bytes, name, "image/*"));
            if (ragOcrOptimizer.isUsefulOcrText(ocrText)) {
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
                selected.add(new RagChunkMatch(c.getDocId(), content, selectedScores.get(i), c.getChunkMetadata()));
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

    private String serializeChunkMetadata(PreparedRagChunk chunk) {
        try {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("blockType", chunk.getBlockType());
            metadata.put("headings", chunk.getHeadings());
            metadata.put("ordinal", chunk.getOrdinal());
            metadata.put("tokenCount", chunk.getTokenCount());
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private String renderChunkForContext(RagChunkMatch match) {
        if (match == null || !StringUtils.hasText(match.getContent())) {
            return "";
        }
        String content = match.getContent().trim();
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

    private record ParagraphCandidate(String text, double score, int index, String section) {
    }

    private record SectionParagraph(String text, String section) {
    }
}



