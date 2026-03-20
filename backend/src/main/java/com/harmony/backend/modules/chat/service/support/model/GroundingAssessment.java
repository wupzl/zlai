package com.harmony.backend.modules.chat.service.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundingAssessment {
    private String status;
    private double groundingScore;
    private int evidenceCount;
    private int eligibleCitationCount;
    private String fallbackReason;
    private String policyVersion;
}
