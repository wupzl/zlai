package com.harmony.backend.modules.chat.service.support;

import com.harmony.backend.common.response.PageResult;
import com.harmony.backend.modules.chat.controller.response.ChatSessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class ChatSessionCacheService {

    private static final String SESSION_BELONG_KEY_PREFIX = "session:belong:";
    private static final String USER_SESSION_CACHE_KEY_PREFIX = "user:sessions:v5:";
    private static final String USER_SESSION_VERSION_KEY_PREFIX = "user:sessions:version:";

    private final RedisTemplate<String, Object> redisTemplate;

    public Boolean getCachedBelong(Long userId, String chatId) {
        return (Boolean) redisTemplate.opsForValue().get(buildBelongKey(userId, chatId));
    }

    public void cacheBelong(Long userId, String chatId, boolean belong) {
        redisTemplate.opsForValue().set(
                buildBelongKey(userId, chatId),
                belong,
                belong ? Duration.ofHours(2) : Duration.ofMinutes(5)
        );
    }

    @SuppressWarnings("unchecked")
    public PageResult<ChatSessionVO> getCachedSessions(Long userId, int page, int size) {
        return (PageResult<ChatSessionVO>) redisTemplate.opsForValue().get(buildSessionsKey(userId, page, size));
    }

    public void cacheSessions(Long userId, int page, int size, PageResult<ChatSessionVO> result) {
        redisTemplate.opsForValue().set(buildSessionsKey(userId, page, size), result, Duration.ofMinutes(10));
    }

    public void invalidateUserSessions(Long userId) {
        String versionKey = buildVersionKey(userId);
        Long version = redisTemplate.opsForValue().increment(versionKey);
        if (version != null && version == 1L) {
            redisTemplate.expire(versionKey, Duration.ofDays(30));
        }
    }

    private String buildSessionsKey(Long userId, int page, int size) {
        long version = resolveVersion(userId);
        return USER_SESSION_CACHE_KEY_PREFIX + userId + ":v:" + version + ":page:" + page + ":size:" + size;
    }

    private long resolveVersion(Long userId) {
        Object raw = redisTemplate.opsForValue().get(buildVersionKey(userId));
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String buildVersionKey(Long userId) {
        return USER_SESSION_VERSION_KEY_PREFIX + userId;
    }

    private String buildBelongKey(Long userId, String chatId) {
        return SESSION_BELONG_KEY_PREFIX + userId + ":" + chatId;
    }
}
