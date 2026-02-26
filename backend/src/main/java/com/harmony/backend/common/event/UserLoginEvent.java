package com.harmony.backend.common.event;

import lombok.Getter;

@Getter
public class UserLoginEvent extends BaseEvent {
    private final Long userId;

    public UserLoginEvent(Object source, Long userId) {
        super(source);
        this.userId = userId;
    }
}
