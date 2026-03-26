package com.harmony.backend.ai.agent.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentAutonomyPolicy {

    private final int maxStepsPerInvocation;
    private final int maxToolCallsPerInvocation;
    private final long maxRuntimeMs;
    private final boolean waitOnBudgetExhausted;

    public AgentAutonomyPolicy(@Value("${app.agents.runtime.max-steps-per-invocation:8}") int maxStepsPerInvocation,
                               @Value("${app.agents.runtime.max-tool-calls-per-invocation:4}") int maxToolCallsPerInvocation,
                               @Value("${app.agents.runtime.max-runtime-ms:30000}") long maxRuntimeMs,
                               @Value("${app.agents.runtime.wait-on-budget-exhausted:true}") boolean waitOnBudgetExhausted) {
        this.maxStepsPerInvocation = maxStepsPerInvocation;
        this.maxToolCallsPerInvocation = maxToolCallsPerInvocation;
        this.maxRuntimeMs = maxRuntimeMs;
        this.waitOnBudgetExhausted = waitOnBudgetExhausted;
    }

    public int getMaxStepsPerInvocation() {
        return maxStepsPerInvocation;
    }

    public int getMaxToolCallsPerInvocation() {
        return maxToolCallsPerInvocation;
    }

    public long getMaxRuntimeMs() {
        return maxRuntimeMs;
    }

    public boolean isWaitOnBudgetExhausted() {
        return waitOnBudgetExhausted;
    }
}
