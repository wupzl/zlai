package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.GroundingTraceDiagnostics;
import com.harmony.backend.modules.chat.service.support.model.RagCitationDiagnostics;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GroundingTraceService {

    public GroundingTraceDiagnostics build(String chatId,
                                           String assistantMessageId,
                                           String query,
                                           GroundingAssessment groundingAssessment,
                                           RagCitationDiagnostics citationDiagnostics) {
        String status = groundingAssessment != null ? groundingAssessment.getStatus() : "none";
        double groundingScore = groundingAssessment != null ? groundingAssessment.getGroundingScore() : 0d;
        String fallbackReason = groundingAssessment != null ? groundingAssessment.getFallbackReason() : null;
        String policyVersion = groundingAssessment != null ? groundingAssessment.getPolicyVersion() : null;
        int eligibleCitationCount = groundingAssessment != null ? groundingAssessment.getEligibleCitationCount() : 0;
        int evidenceCount = citationDiagnostics != null ? citationDiagnostics.getEvidenceCount() : 0;
        int selectedCitationCount = citationDiagnostics != null ? citationDiagnostics.getSelectedCitationCount() : 0;
        List<String> selectedDocIds = citationDiagnostics != null ? citationDiagnostics.getSelectedDocIds() : List.of();
        List<String> selectedTitles = citationDiagnostics != null ? citationDiagnostics.getSelectedTitles() : List.of();
        List<Double> citationScores = citationDiagnostics != null ? citationDiagnostics.getCitationScores() : List.of();
        boolean downgraded = "partial".equals(status) || "insufficient_evidence".equals(status);
        return GroundingTraceDiagnostics.builder()
                .chatId(chatId)
                .assistantMessageId(assistantMessageId)
                .query(query)
                .status(status)
                .groundingScore(groundingScore)
                .fallbackReason(fallbackReason)
                .policyVersion(policyVersion)
                .downgraded(downgraded)
                .evidenceCount(evidenceCount)
                .eligibleCitationCount(eligibleCitationCount)
                .selectedCitationCount(selectedCitationCount)
                .selectedDocIds(selectedDocIds)
                .selectedTitles(selectedTitles)
                .citationScores(citationScores)
                .build();
    }
}
