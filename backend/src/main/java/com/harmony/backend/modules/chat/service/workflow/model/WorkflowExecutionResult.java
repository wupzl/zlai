package com.harmony.backend.modules.chat.service.workflow.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WorkflowExecutionResult {
    private boolean handled;
    private String workflowKey;
    private String content;

    public static WorkflowExecutionResult notHandled() {
        return new WorkflowExecutionResult(false, null, null);
    }

    public static WorkflowExecutionResult handled(String workflowKey, String content) {
        return new WorkflowExecutionResult(true, workflowKey, content);
    }
}