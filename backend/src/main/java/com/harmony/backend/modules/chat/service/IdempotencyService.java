package com.harmony.backend.modules.chat.service;

import com.harmony.backend.common.entity.ChatRequestIdempotency;

public interface IdempotencyService {
    IdempotencyTicket acquire(Long userId, String requestId, String requestType,
                              String chatId, String messageId, String requestHash, int regenerateDedupSeconds);

    void markDone(ChatRequestIdempotency record, String responseContent,
                  String chatId, String messageId, String responseMessageId);

    void markFailed(ChatRequestIdempotency record, String errorMessage);

    void markInterrupted(ChatRequestIdempotency record, String errorMessage);

    String hashFor(Object... parts);

    final class IdempotencyTicket {
        private final ChatRequestIdempotency record;
        private final String replayResponse;
        private final boolean inProgress;

        public IdempotencyTicket(ChatRequestIdempotency record, String replayResponse, boolean inProgress) {
            this.record = record;
            this.replayResponse = replayResponse;
            this.inProgress = inProgress;
        }

        public ChatRequestIdempotency getRecord() {
            return record;
        }

        public String getReplayResponse() {
            return replayResponse;
        }

        public boolean isInProgress() {
            return inProgress;
        }
    }
}
