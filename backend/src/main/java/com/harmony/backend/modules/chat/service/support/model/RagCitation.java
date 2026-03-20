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
public class RagCitation {
    private String docId;
    private String title;
    private String sourcePath;
    private List<String> headings;
    private String excerpt;
    private double retrievalScore;
    private double citationScore;
}
