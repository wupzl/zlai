
package com.harmony.backend.modules.user.service.impl;

import com.harmony.backend.modules.user.service.UserSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed token and user activity checks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSecurityServiceImpl implements UserSecurityService {

    private final RedisTemplate<String, String> redisTemplate;

    // Redis Key
    private static final String TOKEN_BLACKLIST_KEY_PREFIX = "token:blacklist:";
    private static final String LAST_TOKEN_ISSUED_KEY_PREFIX = "user:lastIssued:";
    private static final String USER_ACTIVE_KEY_PREFIX = "user:active:";

    @Override
    public boolean isTokenBlacklisted(String token) {
        String key = TOKEN_BLACKLIST_KEY_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public boolean validateTokenIssuedTime(Long userId, Date issuedAt) {
        if (issuedAt == null) {
            return false;
        }

        String key = LAST_TOKEN_ISSUED_KEY_PREFIX + userId;
        String lastIssuedStr = redisTemplate.opsForValue().get(key);

        if (lastIssuedStr == null) {
            recordTokenIssuedTime(userId, issuedAt);
            return true;
        }

        try {
            long lastIssuedTime = Long.parseLong(lastIssuedStr);
            return issuedAt.getTime() >= lastIssuedTime;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void recordTokenIssuedTime(Long userId, Date issuedAt) {
        if (issuedAt == null) {
            return;
        }
        String key = LAST_TOKEN_ISSUED_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(
                key,
                String.valueOf(issuedAt.getTime()),
                7,
                TimeUnit.DAYS
        );
    }

    @Async
    @Override
    public void updateLastActiveTime(Long userId) {
        String key = USER_ACTIVE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(
                key,
                String.valueOf(System.currentTimeMillis()),
                Duration.ofMinutes(30)
        );
    }
}
