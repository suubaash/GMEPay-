package com.gme.pay.ratefx.config;

import com.gme.pay.ratefx.quote.InMemoryQuoteTtlStore;
import com.gme.pay.ratefx.quote.QuoteTtlStore;
import com.gme.pay.ratefx.quote.RedisQuoteTtlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Wires the {@link QuoteTtlStore} port (17.3-G01): Redis-backed when an explicit
 * Redis host is configured ({@code spring.data.redis.host}), in-memory default
 * otherwise. Declaration order matters: the Redis bean is evaluated first so the
 * {@code @ConditionalOnMissingBean} fallback only kicks in when Redis is absent.
 */
@Configuration
public class QuoteStoreConfig {

    /** Redis rate lock — quotes survive service restarts within their TTL. */
    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.host")
    public QuoteTtlStore redisQuoteTtlStore(StringRedisTemplate redisTemplate) {
        return new RedisQuoteTtlStore(redisTemplate);
    }

    /** Default: process-local store so the service runs without any Redis. */
    @Bean
    @ConditionalOnMissingBean(QuoteTtlStore.class)
    public QuoteTtlStore inMemoryQuoteTtlStore() {
        return new InMemoryQuoteTtlStore();
    }

    /** UTC clock for quote issuance/expiry timestamps (overridable in tests). */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock quoteClock() {
        return Clock.systemUTC();
    }
}
