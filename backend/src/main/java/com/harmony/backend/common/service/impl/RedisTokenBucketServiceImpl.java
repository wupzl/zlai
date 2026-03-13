package com.harmony.backend.common.service.impl;

import com.harmony.backend.common.service.RedisTokenBucketService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RedisTokenBucketServiceImpl implements RedisTokenBucketService {

    private static final DefaultRedisScript<List> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();

    static {
        TOKEN_BUCKET_SCRIPT.setResultType(List.class);
        TOKEN_BUCKET_SCRIPT.setScriptText("""
                local key = KEYS[1]
                local now = tonumber(ARGV[1])
                local refill_per_sec = tonumber(ARGV[2])
                local capacity = tonumber(ARGV[3])
                local requested = tonumber(ARGV[4])
                local ttl_ms = tonumber(ARGV[5])

                local bucket = redis.call('HMGET', key, 'tokens', 'ts')
                local tokens = tonumber(bucket[1])
                local ts = tonumber(bucket[2])

                if tokens == nil then
                    tokens = capacity
                end
                if ts == nil then
                    ts = now
                end

                local elapsed = math.max(0, now - ts)
                local replenished = elapsed * refill_per_sec / 1000.0
                tokens = math.min(capacity, tokens + replenished)

                local allowed = 0
                if tokens >= requested then
                    tokens = tokens - requested
                    allowed = 1
                end

                redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
                redis.call('PEXPIRE', key, ttl_ms)
                return {allowed, tokens}
                """);
    }

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public boolean tryConsume(String key, int capacity, int windowSeconds) {
        if (capacity <= 0 || windowSeconds <= 0) {
            return false;
        }
        double refillPerSecond = (double) capacity / (double) windowSeconds;
        long now = System.currentTimeMillis();
        long ttlMs = Duration.ofSeconds(Math.max(windowSeconds * 2L, 60L)).toMillis();
        List result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key),
                now,
                refillPerSecond,
                capacity,
                1,
                ttlMs
        );
        if (result == null || result.isEmpty()) {
            return true;
        }
        Object allowed = result.get(0);
        if (allowed instanceof Number number) {
            return number.longValue() == 1L;
        }
        return "1".equals(String.valueOf(allowed));
    }
}
