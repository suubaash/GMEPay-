package com.gme.pay.ratefx.config;

import com.gme.pay.ratefx.persistence.RateQuoteRepository;
import com.gme.pay.ratefx.quote.JpaQuoteTtlStore;
import com.gme.pay.ratefx.quote.QuoteTtlStore;
import com.gme.pay.ratefx.quote.RedisQuoteTtlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * Wires the {@link QuoteTtlStore} port (17.3-G01): Redis-backed when an explicit Redis host is
 * configured ({@code spring.data.redis.host}); otherwise a durable, restart-safe DB-backed store
 * ({@link JpaQuoteTtlStore} over the {@code rate_quotes} audit table) — so quote locks survive a
 * restart <em>without</em> requiring Redis. Declaration order matters: Redis is evaluated first so the
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

    /**
     * Default (no Redis): durable DB-backed store. The lock lives in the {@code rate_quotes} row's
     * {@code expires_at}, so issued quotes survive a service restart for the remainder of their TTL.
     */
    @Bean
    @ConditionalOnMissingBean(QuoteTtlStore.class)
    public QuoteTtlStore jpaQuoteTtlStore(RateQuoteRepository repository, Clock clock) {
        return new JpaQuoteTtlStore(repository, clock);
    }

    /** UTC clock for quote issuance/expiry timestamps (overridable in tests). */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock quoteClock() {
        return Clock.systemUTC();
    }
}
