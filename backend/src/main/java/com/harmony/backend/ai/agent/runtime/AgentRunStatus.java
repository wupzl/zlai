package com.harmony.backend.ai.agent.runtime;

public final class AgentRunStatus {
    public static final String PLANNED = "PLANNED";
    public static final String RUNNING = "RUNNING";
    public static final String WAITING = "WAITING";
    public static final String RESUMED = "RESUMED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";

    private AgentRunStatus() {
    }
}
