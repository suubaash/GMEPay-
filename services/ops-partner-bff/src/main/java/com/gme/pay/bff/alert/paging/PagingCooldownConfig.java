package com.gme.pay.bff.alert.paging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Locale;

/**
 * The one place the paging cooldown is bound to a backing store.
 *
 * <p>{@code gmepay.ops.paging.cooldown-store}: {@code auto} (default — Redis when
 * {@code spring.data.redis.host} is set, per-JVM otherwise), {@code redis} (refuse to start without
 * a host) or {@code memory}. Anything else refuses to start, for the same reason as the gateway's
 * equivalent switch: a typo must not silently select a weaker control.
 *
 * <p>Redis is always wrapped in {@link FailoverPagingCooldown}, so an unreachable Redis degrades
 * paging dedupe to per-replica instead of suppressing pages. That policy, and why it inverts the
 * gateway's, is documented on that class.
 */
@Configuration
public class PagingCooldownConfig {

    public static final String STORE_PROPERTY = "gmepay.ops.paging.cooldown-store";
    public static final String REDIS_HOST_PROPERTY = "spring.data.redis.host";

    public static final String AUTO = "auto";
    public static final String REDIS = "redis";
    public static final String MEMORY = "memory";

    private static final Logger log = LoggerFactory.getLogger(PagingCooldownConfig.class);

    @Bean
    public PagingCooldown pagingCooldown(Environment env,
                                         ObjectProvider<StringRedisTemplate> redis) {
        if (!useRedis(env)) {
            return new InMemoryPagingCooldown();
        }
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("ops-partner-bff refuses to start: "
                    + STORE_PROPERTY + " selects Redis but no StringRedisTemplate is available "
                    + "(spring-boot-starter-data-redis must be on the classpath).");
        }
        return new FailoverPagingCooldown(
                new RedisPagingCooldown(template), new InMemoryPagingCooldown());
    }

    /** Package-visible + static so the decision table is testable without a Spring context. */
    static boolean useRedis(Environment env) {
        String raw = env.getProperty(STORE_PROPERTY, AUTO);
        String mode = raw == null ? AUTO : raw.trim().toLowerCase(Locale.ROOT);
        String host = env.getProperty(REDIS_HOST_PROPERTY);
        boolean hostConfigured = host != null && !host.isBlank();

        switch (mode) {
            case REDIS -> {
                if (!hostConfigured) {
                    throw new IllegalStateException("ops-partner-bff refuses to start: "
                            + STORE_PROPERTY + "=redis but " + REDIS_HOST_PROPERTY + " is not set. "
                            + "Set SPRING_DATA_REDIS_HOST; starting with the per-JVM cooldown "
                            + "instead would page a human once per replica.");
                }
                log.info("ops paging cooldown: REDIS ({}) — dedupe is fleet-wide", host);
                return true;
            }
            case MEMORY -> {
                log.warn("ops paging cooldown: MEMORY (explicit). Dedupe is PER-REPLICA: at N>1 a "
                        + "re-firing alert can page a human once per replica.");
                return false;
            }
            case AUTO -> {
                if (hostConfigured) {
                    log.info("ops paging cooldown: REDIS ({}) by auto-detection of {}",
                            host, REDIS_HOST_PROPERTY);
                    return true;
                }
                log.warn("ops paging cooldown: MEMORY — {} is unset. Dedupe is per-replica; "
                        + "correct for local/dev, not for a deployment that scales.",
                        REDIS_HOST_PROPERTY);
                return false;
            }
            default -> throw new IllegalStateException("ops-partner-bff refuses to start: "
                    + STORE_PROPERTY + "='" + raw + "' is not a recognised value. Use one of: "
                    + AUTO + ", " + REDIS + ", " + MEMORY + ".");
        }
    }
}
