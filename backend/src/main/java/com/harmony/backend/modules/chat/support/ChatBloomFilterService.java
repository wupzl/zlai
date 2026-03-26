package com.harmony.backend.modules.chat.support;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class ChatBloomFilterService {

    private static final int LOCK_SEGMENTS = 16;

    private final BloomFilter<CharSequence> messageBloom =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1_000_000, 0.01);
    private final BloomFilter<CharSequence> requestBloom =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1_000_000, 0.01);
    private final ReentrantReadWriteLock[] messageLocks = createLocks();
    private final ReentrantReadWriteLock[] requestLocks = createLocks();

    public boolean mightContainMessage(String messageId) {
        if (messageId == null) {
            return false;
        }
        Lock lock = messageLocks[indexFor(messageId)].readLock();
        lock.lock();
        try {
            return messageBloom.mightContain(messageId);
        } finally {
            lock.unlock();
        }
    }

    public void putMessage(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            Lock lock = messageLocks[indexFor(messageId)].writeLock();
            lock.lock();
            try {
                messageBloom.put(messageId);
            } finally {
                lock.unlock();
            }
        }
    }

    public boolean mightContainRequest(Long userId, String requestId) {
        if (requestId == null) {
            return false;
        }
        String requestKey = buildRequestKey(userId, requestId);
        Lock lock = requestLocks[indexFor(requestKey)].readLock();
        lock.lock();
        try {
            return requestBloom.mightContain(requestKey);
        } finally {
            lock.unlock();
        }
    }

    public void putRequest(Long userId, String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            String requestKey = buildRequestKey(userId, requestId);
            Lock lock = requestLocks[indexFor(requestKey)].writeLock();
            lock.lock();
            try {
                requestBloom.put(requestKey);
            } finally {
                lock.unlock();
            }
        }
    }

    private ReentrantReadWriteLock[] createLocks() {
        ReentrantReadWriteLock[] locks = new ReentrantReadWriteLock[LOCK_SEGMENTS];
        for (int i = 0; i < LOCK_SEGMENTS; i++) {
            locks[i] = new ReentrantReadWriteLock();
        }
        return locks;
    }

    private int indexFor(String key) {
        return Math.floorMod(key.hashCode(), LOCK_SEGMENTS);
    }

    private String buildRequestKey(Long userId, String requestId) {
        return (userId == null ? "anonymous" : userId) + ":" + requestId;
    }
}
