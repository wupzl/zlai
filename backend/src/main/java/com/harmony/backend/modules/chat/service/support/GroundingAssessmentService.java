package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class GroundingAssessmentService {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+");

    private final RagProperties ragProperties;

    public GroundingAssessment assess(String answerContent,
                                      ResolvedRagEvidence ragEvidence,
                                      List<RagCitation> citations) {
        int evidenceCount = ragEvidence == null || ragEvidence.getMatches() == null
                ? 0
                : ragEvidence.getMatches().size();
        int eligibleCitationCount = citations == null ? 0 : citations.size();
        double groundingScore = computeGroundingScore(answerContent, ragEvidence, citations);
        RagProperties.Grounding config = ragProperties.getGrounding();

        if (!config.isEnabled()) {
            return null;
        }
        if (evidenceCount <= 0) {
            return build("insufficient_evidence", groundingScore, evidenceCount, eligibleCitationCount, "no_retrieved_evidence", config);
        }
        if (eligibleCitationCount < Math.max(1, config.getMinCitationCount())) {
            return build("insufficient_evidence", groundingScore, evidenceCount, eligibleCitationCount, "no_eligible_citations", config);
        }
        if (groundingScore >= config.getMinGroundedScore()) {
            return build("grounded", groundingScore, evidenceCount, eligibleCitationCount, null, config);
        }
        if (groundingScore >= config.getMinPartialScore()) {
            if (!config.isAllowPartialAnswer()) {
                return build("insufficient_evidence", groundingScore, evidenceCount, eligibleCitationCount, "partial_disabled", config);
            }
            return build("partial", groundingScore, evidenceCount, eligibleCitationCount, "partial_answer_only", config);
        }
        return build("insufficient_evidence", groundingScore, evidenceCount, eligibleCitationCount, "weak_evidence_overlap", config);
    }

    private GroundingAssessment build(String status,
                                      double groundingScore,
                                      int evidenceCount,
                                      int eligibleCitationCount,
                                      String fallbackReason,
                                      RagProperties.Grounding config) {
        return GroundingAssessment.builder()
                .status(status)
                .groundingScore(round(groundingScore))
                .evidenceCount(evidenceCount)
                .eligibleCitationCount(eligibleCitationCount)
                .fallbackReason(fallbackReason)
                .policyVersion(config.getPolicyVersion())
                .build();
    }

    private double computeGroundingScore(String answerContent,
                                         ResolvedRagEvidence ragEvidence,
                                         List<RagCitation> citations) {
        if (!StringUtils.hasText(answerContent) || ragEvidence == null) {
            return 0d;
        }
        Set<String> answerTokens = tokenize(answerContent);
        if (answerTokens.isEmpty()) {
            return 0d;
        }
        Set<String> evidenceTokens = new LinkedHashSet<>();
        if (StringUtils.hasText(ragEvidence.getContext())) {
            evidenceTokens.addAll(tokenize(ragEvidence.getContext()));
        }
        if (citations != null) {
            for (RagCitation citation : citations) {
                if (citation == null) {
                    continue;
                }
                evidenceTokens.addAll(tokenize(citation.getExcerpt()));
                evidenceTokens.addAll(tokenize(citation.getTitle()));
                if (citation.getHeadings() != null) {
                    for (String heading : citation.getHeadings()) {
                        evidenceTokens.addAll(tokenize(heading));
                    }
                }
            }
        }
        if (evidenceTokens.isEmpty()) {
            return 0d;
        }
        int overlap = 0;
        for (String token : answerTokens) {
            if (evidenceTokens.contains(token)) {
                overlap++;
            }
        }
        double coverage = overlap / (double) Math.max(1, Math.min(answerTokens.size(), 12));
        double citationBoost = citations == null || citations.isEmpty()
                ? 0d
                : Math.min(0.2d, citations.size() * 0.08d);
        return Math.min(1d, coverage + citationBoost);
    }

    private Set<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Set.of();
        }
        String normalized = NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
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

    private double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
