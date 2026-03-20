package com.harmony.backend.modules.chat.service.orchestration.model;

import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.service.support.model.ResolvedRagEvidence;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PreparedSyncMessage {
    private Session session;
    private String chatId;
    private String finalModel;
    private Agent sessionAgent;
    private boolean multiAgentEnabled;
    private String userMessageId;
    private int promptTokens;
    private String warning;
    private LlmAdapter adapter;
    private List<LlmMessage> messages;
    private ResolvedRagEvidence ragEvidence;
    private String immediateResponse;

    public static PreparedSyncMessage immediate(String response) {
        return new PreparedSyncMessage(null, null, null, null, false, null, 0, null, null, null, null, response);
    }

    public static PreparedSyncMessage ready(Session session, String chatId, String finalModel, Agent sessionAgent,
                                            boolean multiAgentEnabled, String userMessageId, int promptTokens,
                                            String warning, LlmAdapter adapter, List<LlmMessage> messages,
                                            ResolvedRagEvidence ragEvidence) {
        return new PreparedSyncMessage(session, chatId, finalModel, sessionAgent, multiAgentEnabled,
                userMessageId, promptTokens, warning, adapter, messages, ragEvidence, null);
    }
}
