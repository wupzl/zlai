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
public class RagCitationDiagnostics {
    private String query;
    private int evidenceCount;
    private int selectedCitationCount;
    private List<String> selectedDocIds;
    private List<String> selectedTitles;
    private List<Double> citationScores;
}
