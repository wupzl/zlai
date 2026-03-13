package com.harmony.backend.modules.chat.support;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class ChatBloomFilterService {

    private final BloomFilter<CharSequence> messageBloom =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1_000_000, 0.01);
    private final BloomFilter<CharSequence> requestBloom =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1_000_000, 0.01);

    public synchronized boolean mightContainMessage(String messageId) {
        return messageId != null && messageBloom.mightContain(messageId);
    }

    public synchronized void putMessage(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            messageBloom.put(messageId);
        }
    }

    public synchronized boolean mightContainRequest(Long userId, String requestId) {
        return requestId != null && requestBloom.mightContain(buildRequestKey(userId, requestId));
    }

    public synchronized void putRequest(Long userId, String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            requestBloom.put(buildRequestKey(userId, requestId));
        }
    }

    private String buildRequestKey(Long userId, String requestId) {
        return (userId == null ? "anonymous" : userId) + ":" + requestId;
    }
}
