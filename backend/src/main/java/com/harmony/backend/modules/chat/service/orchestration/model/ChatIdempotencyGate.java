package com.harmony.backend.modules.chat.service.orchestration.model;

import com.harmony.backend.common.entity.ChatRequestIdempotency;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatIdempotencyGate {
    private ChatRequestIdempotency record;
    private String replayResponse;
    private boolean inProgress;
}
