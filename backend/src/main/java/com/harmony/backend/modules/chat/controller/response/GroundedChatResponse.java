package com.harmony.backend.modules.chat.controller.response;

import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundedChatResponse {
    private String content;
    private List<RagCitation> citations;
    private GroundingAssessment grounding;
}
