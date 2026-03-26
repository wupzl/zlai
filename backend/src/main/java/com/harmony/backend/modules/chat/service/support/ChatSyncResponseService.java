package com.harmony.backend.modules.chat.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harmony.backend.modules.chat.controller.response.GroundedChatResponse;
import com.harmony.backend.modules.chat.service.support.model.GroundingAssessment;
import com.harmony.backend.modules.chat.service.support.model.RagCitation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSyncResponseService {

    private final ObjectMapper objectMapper;

    public Object buildPayload(String finalContent,
                               List<RagCitation> citations,
                               GroundingAssessment groundingAssessment) {
        boolean hasCitations = citations != null && !citations.isEmpty();
        boolean hasGrounding = groundingAssessment != null;
        if (!hasCitations && !hasGrounding) {
            return finalContent;
        }
        return GroundedChatResponse.builder()
                .content(finalContent)
                .citations(hasCitations ? citations : List.of())
                .grounding(groundingAssessment)
                .build();
    }

    public String serializeForIdempotency(Object responsePayload) {
        if (responsePayload instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(responsePayload);
        } catch (Exception e) {
            log.warn("Serialize sync response for idempotency failed, fallback to content only", e);
            if (responsePayload instanceof GroundedChatResponse grounded) {
                return grounded.getContent() == null ? "" : grounded.getContent();
            }
            return String.valueOf(responsePayload);
        }
    }

    public Object decodeReplayResponse(String replayResponse) {
        if (replayResponse == null || replayResponse.isBlank()) {
            return replayResponse;
        }
        String trimmed = replayResponse.trim();
        if (!trimmed.startsWith("{")) {
            return replayResponse;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isObject() && root.has("content")) {
                return objectMapper.treeToValue(root, GroundedChatResponse.class);
            }
        } catch (Exception e) {
            log.debug("Replay response is not structured grounded chat payload");
        }
        return replayResponse;
    }
}
