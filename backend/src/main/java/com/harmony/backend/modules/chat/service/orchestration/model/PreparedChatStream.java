package com.harmony.backend.modules.chat.service.orchestration.model;

import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmAdapter;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PreparedChatStream {
    private Session session;
    private String finalModel;
    private Agent sessionAgent;
    private boolean multiAgentEnabled;
    private boolean bufferToolStream;
    private String userMessageId;
    private int promptTokens;
    private String warning;
    private LlmAdapter adapter;
    private List<LlmMessage> messages;
    private String assistantMessageId;
    private String immediateResponse;

    public static PreparedChatStream immediate(String response) {
        return new PreparedChatStream(null, null, null, false, false, null, 0, null, null, null, null, response);
    }

    public static PreparedChatStream ready(Session session, String finalModel, Agent sessionAgent,
                                           boolean multiAgentEnabled, boolean bufferToolStream, String userMessageId,
                                           int promptTokens, String warning, LlmAdapter adapter,
                                           List<LlmMessage> messages, String assistantMessageId) {
        return new PreparedChatStream(session, finalModel, sessionAgent, multiAgentEnabled, bufferToolStream,
                userMessageId, promptTokens, warning, adapter, messages, assistantMessageId, null);
    }
}
