package com.gme.pay.bff.alert;

import com.gme.pay.bff.persistence.OpsAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * Wiring for the ops-alert read model (replica ceiling; gap T3-3 consumer half).
 *
 * <h2>What changed and why</h2>
 * {@link OpsAlertStore} used to be a concrete {@code @Component} — a 200-entry per-JVM
 * {@code ArrayDeque} with a per-JVM {@code AtomicLong} minting the alert ids. It was the last
 * single-replica ceiling in the platform: at N&gt;1 the alerts list, the ack state and the alert
 * <b>ids</b> all differed per replica, and a restart lost the window. It is now the module's own
 * {@code ops_alerts} table (V001), which additionally makes the ack durable and auditable.
 *
 * <h2>Selection</h2>
 * {@code gmepay.ops.alerts.store}: {@code db} (default) or {@code memory}; <b>anything else refuses
 * to start</b>, because a typo must not silently select a store that re-imposes the ceiling.
 * {@code memory} is legal — it is what unit slices and a laptop with no database use — and logs a
 * {@code WARN} naming every consequence, so a deployment running on it is discoverable from a log
 * rather than from an operator acking the wrong alert.
 *
 * <p>Same decision-table idiom, and the same "refuses to start" wording, as
 * {@code gateway.shared-state.store} (T0-7 / replica ceiling) and
 * {@code gmepay.idempotency.store} (transaction-mgmt V013).
 *
 * <h2>Retention</h2>
 * {@code gmepay.ops.alerts.retention-days}, default {@value #DEFAULT_RETENTION_DAYS} — the same
 * property name and default as payment-executor's emitter-side {@code ops_alerts}, deliberately, so
 * the two halves of one alert's history age out together rather than one outliving the other.
 * <b>This is an engineering default, not a business commitment:</b> a records-retention owner should
 * confirm how long operational alert evidence (and the operator acks on it) must be kept.
 */
@Configuration
public class OpsAlertStoreConfig {

    /** {@code gmepay.ops.alerts.store}. */
    public static final String STORE_PROPERTY = "gmepay.ops.alerts.store";

    public static final String DB = "db";
    public static final String MEMORY = "memory";

    /** Days of alert history kept by default. Mirrors payment-executor's ops_alerts pruner. */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(OpsAlertStoreConfig.class);

    /**
     * The one {@link OpsAlertStore} in the context. {@code @Primary} only so that an unrelated future
     * bean cannot make the injection point ambiguous; the two implementations are never both
     * registered as candidates for this decision.
     */
    @Bean
    @Primary
    public OpsAlertStore opsAlertStore(
            Environment env,
            JpaOpsAlertStore dbStore,
            @Value("${gmepay.ops.alerts.capacity:200}") int capacity) {
        if (useDatabase(env)) {
            return dbStore;
        }
        return new InMemoryOpsAlertStore(capacity);
    }

    /**
     * Always constructed (the retention sweeper is {@code @ConditionalOnBean} on it and the
     * selection needs it available), but only <em>selected</em> when the mode is {@code db}. It holds
     * no state and opens no connection until used, so an unselected instance costs nothing — the same
     * arrangement as {@code transaction-mgmt}'s {@code JdbcIdempotencyStore}.
     */
    @Bean
    public JpaOpsAlertStore jpaOpsAlertStore(
            OpsAlertRepository repository,
            PlatformTransactionManager transactionManager,
            @Value("${gmepay.ops.alerts.retention-days:90}") int retentionDays) {
        int days = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
        return new JpaOpsAlertStore(repository, transactionManager, Clock.systemUTC(),
                Duration.ofDays(days));
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
                String url = env.getProperty("spring.datasource.url", "");
                if (url.startsWith("jdbc:h2:mem")) {
                    // The trap this branch exists to close: mode=db over an in-memory H2 is a
                    // per-JVM store wearing the durable store's name. The shipped image default IS
                    // that H2 (so a laptop boots with no database), and every deployed surface
                    // overrides SPRING_DATASOURCE_URL -- which scripts/check_helm_chart_wiring.py
                    // enforces for exactly this reason.
                    log.warn("ops alert store: DATABASE selected, but the datasource is IN-MEMORY H2 "
                                    + "({}). The table is real and the code path is the durable one, "
                                    + "but the data lives in this JVM: REPLICA CEILING = 1 and the "
                                    + "alerts + acks are lost on restart. Set "
                                    + "SPRING_DATASOURCE_URL to a real PostgreSQL before running "
                                    + "more than one replica.", url);
                    return true;
                }
                log.info("ops alert store: DATABASE (ops_alerts, V001) at {} — the alerts list, the "
                        + "alert ids and the operator acks are shared and durable; ops-partner-bff "
                        + "may run N>1", url.isBlank() ? "the configured datasource" : url);
                return true;
            }
            case MEMORY -> {
                log.warn("ops alert store: MEMORY (selected via {}=memory). REPLICA CEILING = 1: "
                        + "each replica keeps its OWN alert list and its OWN seq counter, so "
                        + "GET /v1/admin/ops/alerts answers differently per replica, an ack on one "
                        + "replica is invisible to the others (whose escalation sweep keeps "
                        + "escalating it), acking id N can hit a DIFFERENT alert, and a restart "
                        + "loses the window. Intended for unit slices and local runs without a "
                        + "database; remove the override to use the durable table.", STORE_PROPERTY);
                return false;
            }
            default -> throw new IllegalStateException(
                    "ops-partner-bff refuses to start: " + STORE_PROPERTY + "='" + raw
                            + "' is not a recognised value. Use one of: " + DB + ", " + MEMORY
                            + ". A typo must not silently select a store that re-imposes the "
                            + "single-replica ceiling on the operator alert surface.");
        }
    }
}
