package com.swiftpay.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed idempotency guard.
 *
 * <p>Uses SET NX EX (atomic) to ensure a given idempotency key is only
 * processed once within the configured TTL window (default 24 h).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:payment:";

    private final StringRedisTemplate redisTemplate;

    @Value("${swiftpay.idempotency.ttl-hours:24}")
    private long ttlHours;

    /**
     * Attempts to claim the idempotency key.
     *
     * @param idempotencyKey the client-supplied key
     * @return {@code true} if the key is new (first request), {@code false} if it's a duplicate
     */
    public boolean tryAcquire(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING", Duration.ofHours(ttlHours));
        boolean acquired = Boolean.TRUE.equals(isNew);
        if (!acquired) {
            log.warn("Idempotency key already exists in Redis: {}", idempotencyKey);
        }
        return acquired;
    }

    /**
     * Mark the key as completed (updates the value, keeps TTL).
     */
    public void markCompleted(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        redisTemplate.opsForValue().set(redisKey, "COMPLETED", Duration.ofHours(ttlHours));
    }

    /**
     * Check if an idempotency key already exists (non-modifying).
     */
    public boolean exists(String idempotencyKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + idempotencyKey));
    }
}
