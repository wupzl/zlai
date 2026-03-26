package com.harmony.backend.modules.chat.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.ai.rag.model.RagChunkMatch;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.modules.chat.service.support.model.RagCitationDiagnostics;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RagCitationService {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+");
    private static final Pattern OBSIDIAN_IMAGE = Pattern.compile("!\\[\\[[^\\]]+\\]\\]");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^\\)]+\\)");
    private static final Pattern PASTED_IMAGE = Pattern.compile("(?im)^\\s*Pasted image[^\\r\\n]*$");

    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public List<RagCitation> deriveCitations(String answerContent, ResolvedRagEvidence ragEvidence) {
        if (ragEvidence == null || !ragEvidence.isEnabled() || !ragEvidence.hasMatches()) {
            return List.of();
        }
        String normalizedAnswer = normalize(answerContent);
        List<RagCitation> citations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RagChunkMatch match : ragEvidence.getMatches()) {
            if (match == null || !StringUtils.hasText(match.getDocId())) {
                continue;
            }
            Map<String, Object> metadata = parseMetadata(match.getChunkMetadata());
            String cleanedContent = cleanEvidenceContent(match.getContent());
            double overlap = lexicalOverlap(normalizedAnswer, normalize(cleanedContent));
            double titleOverlap = lexicalOverlap(normalizedAnswer, normalize(stringValue(metadata.get("title"))));
            double headingOverlap = lexicalOverlap(normalizedAnswer, normalize(String.join(" ", stringList(metadata.get("headings")))));
            if (overlap <= 0d && titleOverlap <= 0d && headingOverlap <= 0d) {
                continue;
            }
            double citationScore = overlap + (titleOverlap * 0.5d) + (headingOverlap * 0.35d) + (match.getScore() * 0.2d);
            String dedupeKey = match.getDocId() + "|" + String.valueOf(metadata.getOrDefault("sourcePath", ""));
            if (!seen.add(dedupeKey)) {
                continue;
            }
            citations.add(RagCitation.builder()
                    .docId(match.getDocId())
                    .title(stringValue(metadata.get("title")))
                    .sourcePath(stringValue(metadata.get("sourcePath")))
                    .headings(stringList(metadata.get("headings")))
                    .excerpt(buildExcerpt(cleanedContent))
                    .retrievalScore(match.getScore())
                    .citationScore(citationScore)
                    .build());
            if (citations.size() >= maxCitationCount()) {
                break;
            }
        }
        citations.sort(Comparator.comparingDouble(RagCitation::getCitationScore).reversed());
        return citations;
    }

    public RagCitationDiagnostics buildDiagnostics(ResolvedRagEvidence ragEvidence, List<RagCitation> citations) {
        List<RagCitation> safeCitations = citations == null ? List.of() : citations;
        return RagCitationDiagnostics.builder()
                .query(ragEvidence == null ? "" : ragEvidence.getQuery())
                .evidenceCount(ragEvidence == null || ragEvidence.getMatches() == null ? 0 : ragEvidence.getMatches().size())
                .selectedCitationCount(safeCitations.size())
                .selectedDocIds(safeCitations.stream().map(RagCitation::getDocId).toList())
                .selectedTitles(safeCitations.stream().map(RagCitation::getTitle).toList())
                .citationScores(safeCitations.stream().map(RagCitation::getCitationScore).toList())
                .build();
    }

    private Map<String, Object> parseMetadata(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private double lexicalOverlap(String answer, String evidence) {
        if (!StringUtils.hasText(answer) || !StringUtils.hasText(evidence)) {
            return 0d;
        }
        Set<String> answerTokens = tokenize(answer);
        Set<String> evidenceTokens = tokenize(evidence);
        if (answerTokens.isEmpty() || evidenceTokens.isEmpty()) {
            return 0d;
        }
        int overlap = 0;
        for (String token : answerTokens) {
            if (evidenceTokens.contains(token)) {
                overlap++;
            }
        }
        return overlap / (double) Math.max(1, Math.min(answerTokens.size(), 12));
    }

    private Set<String> tokenize(String text) {
        String normalized = normalize(text);
        if (!StringUtils.hasText(normalized)) {
            return Set.of();
        }
        String[] parts = normalized.split("\\s+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private String buildExcerpt(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmed = content.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 260 ? trimmed.substring(0, 260).trim() + "..." : trimmed;
    }

    private String cleanEvidenceContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return PASTED_IMAGE.matcher(
                        MARKDOWN_IMAGE.matcher(
                                OBSIDIAN_IMAGE.matcher(content).replaceAll(" ")
                        ).replaceAll(" ")
                ).replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private int maxCitationCount() {
        if (ragProperties == null || ragProperties.getGrounding() == null) {
            return 3;
        }
        return Math.max(1, ragProperties.getGrounding().getMaxCitationCount());
    }
}
