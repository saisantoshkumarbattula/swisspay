package com.swiftpay.gateway.service;

import com.swiftpay.gateway.domain.Wallet;
import com.swiftpay.gateway.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Cached balance lookup service.
 * Redis is used as a read-through cache to avoid hitting PostgreSQL on every balance check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BalanceCacheService {

    private static final String BALANCE_KEY_PREFIX = "balance:";

    private final StringRedisTemplate redisTemplate;
    private final WalletRepository walletRepository;

    /**
     * Returns the cached balance for a user, falling through to PostgreSQL on cache miss.
     */
    public Optional<BigDecimal> getCachedBalance(UUID userId) {
        String key = BALANCE_KEY_PREFIX + userId;
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.debug("Cache HIT for balance of user {}", userId);
            return Optional.of(new BigDecimal(cached));
        }

        log.debug("Cache MISS for balance of user {}, querying DB", userId);
        Optional<Wallet> wallet = walletRepository.findById(userId);
        wallet.ifPresent(w -> {
            redisTemplate.opsForValue().set(key, w.getBalance().toPlainString());
            log.debug("Balance for user {} cached in Redis", userId);
        });
        return wallet.map(Wallet::getBalance);
    }

    /**
     * Invalidates the cached balance for a user (call after any balance mutation).
     */
    public void evictBalance(UUID userId) {
        redisTemplate.delete(BALANCE_KEY_PREFIX + userId);
        log.debug("Balance cache evicted for user {}", userId);
    }
}
