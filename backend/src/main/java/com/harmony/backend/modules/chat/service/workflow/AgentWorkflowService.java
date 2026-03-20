package com.harmony.backend.modules.chat.service.workflow;

import com.harmony.backend.common.entity.Agent;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.modules.chat.adapter.LlmMessage;
import com.harmony.backend.modules.chat.service.workflow.model.WorkflowExecutionResult;

import java.util.List;

public interface AgentWorkflowService {
    WorkflowExecutionResult executeSync(Long userId,
                                        Session session,
                                        Agent agent,
                                        List<LlmMessage> messages,
                                        String model,
                                        String assistantMessageId);

    WorkflowExecutionResult executeStream(Long userId,
                                          Session session,
                                          Agent agent,
                                          List<LlmMessage> messages,
                                          String model,
                                          String assistantMessageId);
}