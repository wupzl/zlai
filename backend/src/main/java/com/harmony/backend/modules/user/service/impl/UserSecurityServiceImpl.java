
package com.harmony.backend.modules.user.service.impl;

import com.harmony.backend.common.entity.User;
import com.harmony.backend.common.mapper.UserMapper;
import com.harmony.backend.modules.user.service.UserSecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed token and user activity checks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSecurityServiceImpl implements UserSecurityService {

    private final RedisTemplate<String, String> redisTemplate;
    private final UserMapper userMapper;
    @Qualifier("userActivityExecutor")
    private final Executor userActivityExecutor;

    @Value("${app.auth.user-cache-ttl-ms:3000}")
    private long userCacheTtlMs;

    private final ConcurrentHashMap<Long, CachedUser> userCache = new ConcurrentHashMap<>();

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

    @Override
    public void updateLastActiveTime(Long userId) {
        try {
            userActivityExecutor.execute(() -> {
                String key = USER_ACTIVE_KEY_PREFIX + userId;
                redisTemplate.opsForValue().set(
                        key,
                        String.valueOf(System.currentTimeMillis()),
                        Duration.ofMinutes(30)
                );
            });
        } catch (RejectedExecutionException ex) {
            log.warn("Skip async user activity update because executor is saturated: userId={}", userId);
        }
    }

    @Override
    public User getCachedUser(Long userId) {
        if (userId == null) {
            return null;
        }
        CachedUser cachedUser = userCache.get(userId);
        long now = System.currentTimeMillis();
        if (cachedUser != null && !cachedUser.isExpired(now)) {
            return cachedUser.user();
        }
        User user = userMapper.selectById(userId);
        User snapshot = copyUser(user);
        userCache.put(userId, new CachedUser(snapshot, now + userCacheTtlMs));
        return snapshot;
    }

    private User copyUser(User user) {
        if (user == null) {
            return null;
        }
        return User.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole())
                .status(user.getStatus())
                .lastPasswordChange(user.getLastPasswordChange())
                .lastLogoutTime(user.getLastLogoutTime())
                .tokenBalance(user.getTokenBalance())
                .ocrBalance(user.getOcrBalance())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .deleted(user.getDeleted())
                .build();
    }

    private record CachedUser(User user, long expiresAt) {
        private boolean isExpired(long now) {
            return now > expiresAt;
        }
    }
}
