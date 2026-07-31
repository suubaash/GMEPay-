package com.gme.pay.txn.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Wiring for the idempotency-key store (ticket 17.3-G02; replica ceiling).
 *
 * <h2>What changed and why</h2>
 * This class used to register {@link InMemoryIdempotencyStore} unconditionally and a Redis store
 * {@code @Primary} whenever {@code spring.data.redis.host} happened to be set. Two problems, and
 * the second was the dangerous one:
 *
 * <ol>
 *   <li><b>The default was per-JVM.</b> docker-compose set no Redis host for this service, so the
 *       24-hour replay window was per-replica: one partner retry landing on another pod created a
 *       <em>second money transaction</em>. That is what held transaction-mgmt at one replica.</li>
 *   <li><b>The alternative was auto-selected by an unrelated variable.</b> The Helm ABI ConfigMap
 *       exports {@code SPRING_DATA_REDIS_HOST} to every pod because api-gateway needs it, so this
 *       service silently used Redis under Helm and memory under compose. Nobody chose either. And
 *       Redis in this deployment is a cache — {@code redis:7-alpine}, no AOF, no replication — so a
 *       Redis restart emptied the window and a retry after it produced the duplicate anyway.</li>
 * </ol>
 *
 * The store is now the service's <b>own database table</b> ({@code idempotency_keys}, V013), which
 * is where this module keeps everything else it cannot afford to lose. The full argument is in the
 * migration; the decisive part is that a row in the same database as the transaction it protects
 * cannot be unavailable while the money path is available, so there is no fail-open/fail-closed
 * question to get wrong on a duplicate-suppression control.
 *
 * <h2>Selection</h2>
 * {@code gmepay.idempotency.store}: {@code db} (default) or {@code memory}; anything else refuses
 * to start, because a typo must not select a weaker store. {@code memory} is legal — it is what the
 * unit slices use — and logs a {@code WARN} naming the N=1 ceiling it imposes, so a deployment
 * running on it is discoverable from a log rather than only from a duplicate transaction.
 */
@Configuration
public class IdempotencyConfig {

    /** Idempotency keys are replayable for 24 hours, then may be reused. */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    /** {@code gmepay.idempotency.store}. */
    public static final String STORE_PROPERTY = "gmepay.idempotency.store";

    public static final String DB = "db";
    public static final String MEMORY = "memory";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConfig.class);

    /**
     * Registered only when {@code memory} is selected, so there is exactly one
     * {@link IdempotencyStore} bean in the context and no {@code @Primary} tie-break deciding a
     * money-path behaviour by bean-definition ordering.
     */
    @Bean
    @Primary
    public IdempotencyStore idempotencyStore(Environment env, JdbcIdempotencyStore dbStore) {
        if (useDatabase(env)) {
            return dbStore;
        }
        return new InMemoryIdempotencyStore(Clock.systemUTC(), IDEMPOTENCY_TTL);
    }

    /**
     * Always constructed (the retention sweeper is {@code @ConditionalOnBean} on it and the store
     * selection needs it available), but only <em>selected</em> when the mode is {@code db}. It
     * holds no state and opens no connection until used, so an unselected instance costs nothing.
     */
    @Bean
    public JdbcIdempotencyStore jdbcIdempotencyStore(DataSource dataSource) {
        return new JdbcIdempotencyStore(
                new JdbcTemplate(dataSource), IDEMPOTENCY_TTL, Clock.systemUTC());
    }

    /**
     * Resolve the configured mode. Static and package-visible so the decision table is testable
     * without a Spring context.
     *
     * @throws IllegalStateException on an unrecognised value — the service refuses to start
     */
    static boolean useDatabase(Environment env) {
        String raw = env.getProperty(STORE_PROPERTY, DB);
        String mode = raw == null ? DB : raw.trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case DB -> {
                log.info("idempotency store: DATABASE (idempotency_keys, V013) — the 24h replay "
                        + "window is shared and durable; transaction-mgmt may run N>1");
                return true;
            }
            case MEMORY -> {
                log.warn("idempotency store: MEMORY (selected via {}=memory). REPLICA CEILING = 1: "
                        + "the 24h replay window is per-JVM, so ONE PARTNER RETRY LANDING ON "
                        + "ANOTHER REPLICA CREATES A SECOND TRANSACTION. Intended for unit slices "
                        + "only; remove the override to use the durable table.", STORE_PROPERTY);
                return false;
            }
            default -> throw new IllegalStateException(
                    "transaction-mgmt refuses to start: " + STORE_PROPERTY + "='" + raw
                            + "' is not a recognised value. Use one of: " + DB + ", " + MEMORY
                            + ". A typo must not silently select a weaker idempotency store on a "
                            + "money path.");
        }
    }
}
