package com.harmony.backend.common.event;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public abstract class BaseEvent extends ApplicationEvent {

    private long currTimestamp;
    private String traceId;
    private String sourceModule;

    public BaseEvent(Object source) {
        super(source);
        this.currTimestamp = System.currentTimeMillis();
    }

    public BaseEvent(Object source, String traceId, String sourceModule) {
        this(source);
        this.traceId = traceId;
        this.sourceModule = sourceModule;
    }
}