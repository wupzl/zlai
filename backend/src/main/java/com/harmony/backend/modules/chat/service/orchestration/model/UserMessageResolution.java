package com.harmony.backend.modules.chat.service.orchestration.model;

import com.harmony.backend.common.entity.Message;

public record UserMessageResolution(Message userMessage, boolean reused, Message latestAssistant) {
}
