package com.harmony.backend.common.event;

import lombok.Getter;

@Getter
public class ChatMessageEvent extends BaseEvent {
    private final String chatId;

    public ChatMessageEvent(Object source, String chatId) {
        super(source);
        this.chatId = chatId;
    }
}
