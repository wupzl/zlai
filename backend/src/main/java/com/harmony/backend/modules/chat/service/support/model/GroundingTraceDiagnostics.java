package com.harmony.backend.modules.chat.service.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundingTraceDiagnostics {
    private String chatId;
    private String assistantMessageId;
    private String query;
    private String status;
    private double groundingScore;
    private String fallbackReason;
    private String policyVersion;
    private boolean downgraded;
    private int evidenceCount;
    private int eligibleCitationCount;
    private int selectedCitationCount;
    private List<String> selectedDocIds;
    private List<String> selectedTitles;
    private List<Double> citationScores;
}
