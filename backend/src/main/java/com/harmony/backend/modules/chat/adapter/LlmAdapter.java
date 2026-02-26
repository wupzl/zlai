package com.harmony.backend.modules.chat.adapter;

import reactor.core.publisher.Flux;

import java.util.List;

public interface LlmAdapter {
    boolean supports(String model);

    Flux<String> streamChat(List<LlmMessage> messages, String model);

    String chat(List<LlmMessage> messages, String model);
}
