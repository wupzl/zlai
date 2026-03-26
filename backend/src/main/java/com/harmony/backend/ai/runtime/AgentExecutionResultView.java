package com.harmony.backend.ai.runtime;

import java.util.List;

public interface AgentExecutionResultView {
    boolean isSuccess();

    String getOutput();

    String getError();

    String getModel();

    Integer getPromptTokens();

    Integer getCompletionTokens();

    default String getExecutionId() {
        return null;
    }

    default String getStatus() {
        return isSuccess() ? "COMPLETED" : "FAILED";
    }

    default Integer getStepCount() {
        return 0;
    }

    default List<String> getUsedTools() {
        return List.of();
    }
}
