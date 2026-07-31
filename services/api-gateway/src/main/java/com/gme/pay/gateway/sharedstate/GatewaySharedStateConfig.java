package com.gme.pay.gateway.sharedstate;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import com.gme.pay.gateway.ratelimit.RedisRateLimitStore;
import com.gme.pay.gateway.replay.InMemoryNonceStore;
import com.gme.pay.gateway.replay.NonceStore;
import com.gme.pay.gateway.replay.RedisNonceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.util.Locale;

/**
 * The one place the gateway's cross-request state is bound to a backing store.
 *
 * <h2>What this closes</h2>
 * Two edge controls kept their state in a {@code ConcurrentHashMap}: the per-partner rate-limit
 * window and the replay-protection nonce set. Both are <em>wrong</em>, not merely unscalable, above
 * one replica — N replicas mean N x the configured cap, and a captured signed request replayable
 * once per replica. That, plus transaction-mgmt's idempotency store, is what held the whole fleet
 * at one instance after T3-11 made every scheduler distributed-locked.
 *
 * <h2>Selection — one switch, not two</h2>
 * {@code gateway.shared-state.store} governs <b>both</b> stores together:
 *
 * <table><caption>values</caption>
 * <tr><th>value</th><th>behaviour</th></tr>
 * <tr><td>{@code auto} (default)</td><td>Redis when {@code spring.data.redis.host} is set,
 *     memory otherwise. Logs which one it picked and the resulting replica ceiling.</td></tr>
 * <tr><td>{@code redis}</td><td>Redis, and <b>refuse to start</b> if no host is configured —
 *     so "we deployed with Redis" cannot silently mean "we deployed without it".</td></tr>
 * <tr><td>{@code memory}</td><td>Per-JVM. Legal, and logged as {@code WARN} naming the N=1
 *     ceiling, because a single-replica deployment is a decision someone should be able to find
 *     in a log rather than infer from an absent environment variable.</td></tr>
 * <tr><td>anything else</td><td><b>refuse to start</b> — a typo must not select a different
 *     store, which is the same rule T0-7 applied to {@code gateway.partner-credentials.source}.</td></tr>
 * </table>
 *
 * <p>One switch rather than one per control on purpose: the configuration nobody wants and
 * everybody could produce by accident is the half-shared one, where the nonce set is global and
 * the rate-limit window is not. There is no deployment in which that is the intent.
 *
 * <h2>Redis reachability is NOT probed at startup</h2>
 * Lettuce connects lazily, and a startup probe would only move the failure — a Redis that is up at
 * 09:00 and down at 09:05 is the case that matters. The runtime posture
 * ({@code gateway.rate-limit.on-store-error} / {@code gateway.replay-protection.on-store-error})
 * is the answer to an unavailable Redis, and both are documented on their properties classes.
 * Note that the gateway also ships {@code management.health.redis.enabled: true}, so a Redis
 * outage takes the pod out of the readiness rotation; that is consistent with Redis being a hard
 * dependency under the default {@code REJECT} replay posture, and is worth revisiting if an
 * operator moves both controls to {@code LOCAL}.
 */
@Configuration
public class GatewaySharedStateConfig {

    /** {@code gateway.shared-state.store}. */
    public static final String STORE_PROPERTY = "gateway.shared-state.store";

    /** The property whose presence means "a Redis was actually configured for this service". */
    public static final String REDIS_HOST_PROPERTY = "spring.data.redis.host";

    public static final String AUTO = "auto";
    public static final String REDIS = "redis";
    public static final String MEMORY = "memory";

    private static final Logger log = LoggerFactory.getLogger(GatewaySharedStateConfig.class);

    /**
     * Always registered: it is the selected store under {@code memory}, and the degraded fallback
     * under {@code on-store-error=LOCAL}. Not {@code @Primary} — see {@link #rateLimitStore}.
     */
    @Bean
    public InMemoryRateLimitStore localRateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    /** Always registered, for the same two roles as {@link #localRateLimitStore()}. */
    @Bean
    public InMemoryNonceStore localNonceStore() {
        return new InMemoryNonceStore();
    }

    @Bean
    @Primary
    public RateLimitStore rateLimitStore(Environment env,
                                         ObjectProvider<ReactiveStringRedisTemplate> redis,
                                         InMemoryRateLimitStore local) {
        return useRedis(env) ? new RedisRateLimitStore(requireTemplate(redis)) : local;
    }

    @Bean
    @Primary
    public NonceStore nonceStore(Environment env,
                                 ObjectProvider<ReactiveStringRedisTemplate> redis,
                                 InMemoryNonceStore local) {
        return useRedis(env) ? new RedisNonceStore(requireTemplate(redis)) : local;
    }

    private static ReactiveStringRedisTemplate requireTemplate(
            ObjectProvider<ReactiveStringRedisTemplate> redis) {
        ReactiveStringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(refuseToStart(
                    STORE_PROPERTY + " selects Redis but no ReactiveStringRedisTemplate is "
                            + "available. spring-boot-starter-data-redis-reactive must be on the "
                            + "classpath (it is, in this module's build.gradle) and Redis "
                            + "auto-configuration must not be excluded."));
        }
        return template;
    }

    /**
     * Resolve the configured selection against the environment. Package-visible and static so the
     * decision table is unit-testable without a Spring context.
     *
     * @throws IllegalStateException when the configuration cannot be honoured — the service then
     *         refuses to start rather than falling back to a store the operator did not choose
     */
    static boolean useRedis(Environment env) {
        String raw = env.getProperty(STORE_PROPERTY, AUTO);
        String mode = raw == null ? AUTO : raw.trim().toLowerCase(Locale.ROOT);
        String host = env.getProperty(REDIS_HOST_PROPERTY);
        boolean hostConfigured = host != null && !host.isBlank();

        switch (mode) {
            case REDIS -> {
                if (!hostConfigured) {
                    throw new IllegalStateException(refuseToStart(
                            STORE_PROPERTY + "=redis but " + REDIS_HOST_PROPERTY + " is not set. "
                                    + "Set SPRING_DATA_REDIS_HOST (docker-compose and the Helm ABI "
                                    + "ConfigMap both already declare it). Starting with the "
                                    + "per-JVM store instead would silently give you N x the "
                                    + "configured rate limit and one replay per replica."));
                }
                log.info("gateway shared state: REDIS ({}) — rate-limit window and replay nonce "
                        + "set are fleet-wide; the gateway may run N>1", host);
                return true;
            }
            case MEMORY -> {
                log.warn("gateway shared state: MEMORY (explicitly selected via {}=memory). "
                        + "REPLICA CEILING = 1: the per-partner rate limit is enforced per-JVM "
                        + "(N replicas => N x the cap) and a captured signed request is replayable "
                        + "once per replica inside the {}s clock-skew window. Set {}=redis with "
                        + "{} to lift it.",
                        STORE_PROPERTY, 300, STORE_PROPERTY, REDIS_HOST_PROPERTY);
                return false;
            }
            case AUTO -> {
                if (hostConfigured) {
                    log.info("gateway shared state: REDIS ({}) selected by auto-detection of {} — "
                            + "the gateway may run N>1", host, REDIS_HOST_PROPERTY);
                    return true;
                }
                log.warn("gateway shared state: MEMORY — {} is unset so auto-detection found no "
                        + "Redis. REPLICA CEILING = 1 (per-JVM rate limit and per-JVM replay "
                        + "nonce set). This is the correct local/dev posture; in any deployment "
                        + "that scales, set SPRING_DATA_REDIS_HOST.", REDIS_HOST_PROPERTY);
                return false;
            }
            default -> throw new IllegalStateException(refuseToStart(
                    STORE_PROPERTY + "='" + raw + "' is not a recognised value. Use one of: "
                            + AUTO + ", " + REDIS + ", " + MEMORY + ". A typo must not silently "
                            + "select a different store."));
        }
    }

    private static String refuseToStart(String detail) {
        return "api-gateway refuses to start: " + detail;
    }
}
