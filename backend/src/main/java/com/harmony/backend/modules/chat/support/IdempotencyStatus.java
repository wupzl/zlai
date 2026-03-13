package com.harmony.backend.modules.chat.support;

public final class IdempotencyStatus {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String INTERRUPTED = "INTERRUPTED";

    private IdempotencyStatus() {
    }
}
