package com.harmony.backend.ai.runtime;

import java.util.List;

public interface AgentExecutionResultView {
    boolean isSuccess();

    String getOutput();

    String getError();

    String getModel();

    Integer getPromptTokens();

    Integer getCompletionTokens();

    default List<String> getUsedTools() {
        return List.of();
    }
}
