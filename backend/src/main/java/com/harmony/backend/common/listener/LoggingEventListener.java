package com.harmony.backend.common.listener;

import com.harmony.backend.common.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingEventListener {
    @EventListener
    public void handleAllEvents(BaseEvent event) {
        log.info("Event: {}", event.getClass().getSimpleName());
    }
}