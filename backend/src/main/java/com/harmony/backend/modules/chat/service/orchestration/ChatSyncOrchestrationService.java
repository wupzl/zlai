package com.harmony.backend.modules.chat.service.orchestration;

import com.harmony.backend.modules.chat.service.orchestration.model.ChatIdempotencyGate;
import com.harmony.backend.modules.chat.service.orchestration.model.PreparedSyncMessage;
import org.springframework.stereotype.Service;

@Service
public class ChatSyncOrchestrationService {

    public Object execute(PreparedSyncMessage prepared,
                          ChatIdempotencyGate idempotency,
                          ChatSyncCallbacks callbacks) {
        if (prepared.getImmediateResponse() != null) {
            return prepared.getImmediateResponse();
        }
        try {
            return callbacks.execute(prepared, idempotency);
        } catch (Exception e) {
            callbacks.onFailure(prepared, idempotency, e);
            throw e;
        }
    }

    public interface ChatSyncCallbacks {
        Object execute(PreparedSyncMessage prepared, ChatIdempotencyGate gate);

        void onFailure(PreparedSyncMessage prepared, ChatIdempotencyGate gate, Exception error);
    }
}
