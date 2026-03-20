package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.ai.rag.config.RagProperties;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class GroundingFallbackService {

    private final RagProperties ragProperties;

    public String apply(String answerContent,
                        GroundingAssessment groundingAssessment,
                        String insufficientEvidenceMessage) {
        if (groundingAssessment == null || !StringUtils.hasText(groundingAssessment.getStatus())) {
            return answerContent;
        }
        return switch (groundingAssessment.getStatus()) {
            case "grounded" -> answerContent;
            case "partial" -> applyPartial(answerContent);
            case "insufficient_evidence" -> applyInsufficientEvidence(insufficientEvidenceMessage);
            default -> answerContent;
        };
    }

    private String applyPartial(String answerContent) {
        if (!ragProperties.getGrounding().isShowGroundingHint()) {
            return answerContent;
        }
        String partialPrefix = ragProperties.getGrounding().getPartialAnswerPrefix();
        if (!StringUtils.hasText(answerContent)) {
            return StringUtils.hasText(partialPrefix) ? partialPrefix.trim() : "";
        }
        if (StringUtils.hasText(partialPrefix) && answerContent.startsWith(partialPrefix)) {
            return answerContent;
        }
        return (StringUtils.hasText(partialPrefix) ? partialPrefix : "") + answerContent;
    }

    private String applyInsufficientEvidence(String insufficientEvidenceMessage) {
        String configuredMessage = ragProperties.getGrounding().getInsufficientEvidenceMessage();
        if (StringUtils.hasText(configuredMessage)) {
            return configuredMessage;
        }
        if (StringUtils.hasText(insufficientEvidenceMessage)) {
            return insufficientEvidenceMessage;
        }
        return "I could not find enough support in the knowledge base to answer confidently.";
    }
}
