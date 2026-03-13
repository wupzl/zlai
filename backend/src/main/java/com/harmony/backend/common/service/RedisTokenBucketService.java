package com.harmony.backend.common.service;

public interface RedisTokenBucketService {

    boolean tryConsume(String key, int capacity, int windowSeconds);
}
