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
public class PreparedRegenerateStream {
    private Session session;
    private String finalModel;
    private Agent sessionAgent;
    private boolean multiAgentEnabled;
    private boolean bufferToolStream;
    private int promptTokens;
    private String warning;
    private String newAssistantMessageId;
    private String parentUserMessageId;
    private LlmAdapter adapter;
    private List<LlmMessage> messages;
    private String immediateResponse;

    public static PreparedRegenerateStream immediate(String response) {
        return new PreparedRegenerateStream(null, null, null, false, false, 0, null, null, null, null, null, response);
    }

    public static PreparedRegenerateStream ready(Session session, String finalModel, Agent sessionAgent,
                                                 boolean multiAgentEnabled, boolean bufferToolStream, int promptTokens,
                                                 String warning, String newAssistantMessageId, String parentUserMessageId,
                                                 LlmAdapter adapter, List<LlmMessage> messages) {
        return new PreparedRegenerateStream(session, finalModel, sessionAgent, multiAgentEnabled, bufferToolStream,
                promptTokens, warning, newAssistantMessageId, parentUserMessageId, adapter, messages, null);
    }
}
