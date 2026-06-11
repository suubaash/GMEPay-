package com.gme.pay.txn.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Wiring for the idempotency-key store (ticket 17.3-G02).
 *
 * <p>The in-memory store is always registered as the safe local default; the Redis store is
 * added — and marked {@code @Primary} so by-type injection prefers it — only when
 * {@code spring.data.redis.host} is set (docker-compose exports
 * {@code SPRING_DATA_REDIS_HOST}). Registering both unconditionally-vs-conditionally keeps
 * the selection independent of bean-definition ordering.
 */
@Configuration
public class IdempotencyConfig {

    /** Idempotency keys are replayable for 24 hours, then may be reused. */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    @Bean
    public InMemoryIdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore(Clock.systemUTC(), IDEMPOTENCY_TTL);
    }

    @Bean
    @Primary
    @ConditionalOnProperty("spring.data.redis.host")
    public RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate, IDEMPOTENCY_TTL);
    }
}
