package com.gme.pay.auth.audit;

import com.gme.pay.audit.DbAuditPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place auth-identity writes {@code audit_log}. Implements {@link AuthAuditTrail} on top
 * of lib-audit's {@link DbAuditPublisher}, i.e. on the platform's existing ADR-007 SHA-256
 * hash chain — deliberately not a second sealing scheme, because two chain formats in one
 * {@code audit_log} table cannot be verified by one sweep and the weaker one becomes the
 * attack surface.
 *
 * <h2>Rejection rows must outlive the rollback that produced them</h2>
 *
 * <p>{@link #recordRejection} carries {@code @Transactional(propagation = REQUIRES_NEW)}. This
 * is the whole reason the method exists, so it is worth spelling out what it buys:
 *
 * <p>An authentication failure is a path that <i>aborts</i>. The nonce write it may already
 * have made, the entity it loaded, the transaction the caller opened — all of that unwinds. If
 * the audit row were written on that same transaction it would unwind with it, and the audit
 * trail would be systematically empty for exactly the class of event that matters most:
 * rejected credentials. That failure is silent (the write "succeeded"; it simply never
 * committed), which is the worst kind.
 *
 * <p>{@code REQUIRES_NEW} suspends the caller's transaction, runs the INSERT on a second
 * connection, and commits it before returning. {@link DbAuditPublisher} participates via
 * {@code DataSourceUtils}, so it lands on that new transaction's connection rather than the
 * suspended one. The row is then committed whatever the caller subsequently does — including
 * throwing.
 *
 * <p>Two consequences, both intended:
 * <ul>
 *   <li>It costs a second pooled connection for the duration of the INSERT. Acceptable: this
 *       path is a rejection, and a service that cannot spare a connection to record a rejection
 *       cannot record rejections.</li>
 *   <li>The rejection row can commit while the business path rolls back — i.e. the trail can
 *       contain "we rejected this" for an attempt that left no other trace. That asymmetry is
 *       correct, and it is the opposite way round from {@link #record}, where the audit row and
 *       the business row must share a fate.</li>
 * </ul>
 *
 * <p>Both methods are invoked from <i>other</i> beans, never from within this class, so Spring's
 * proxy is always in the path and the propagation actually applies. A self-invocation would
 * silently bypass it, which is why {@link #recordRejection} does not delegate to
 * {@link #record}.
 *
 * <h2>Failure handling</h2>
 *
 * <p>Audit is a tier that must not break the service: a {@code SQLException} inside
 * {@code DbAuditPublisher} is already logged-and-swallowed there. What this class additionally
 * guards is the {@code IllegalArgumentException} lib-audit throws for an unusable actor id.
 * That is a programming error rather than backpressure, so it is logged at ERROR with the event
 * that lost its row — loudly enough to be found, but not by failing a partner's request. A
 * dedicated test asserts the actor vocabulary at every call site so this branch stays
 * theoretical.
 */
@Service
public class AuthAuditService implements AuthAuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditService.class);

    private final DbAuditPublisher publisher;
    private final AuthAuditActorResolver actors;

    public AuthAuditService(DbAuditPublisher publisher, AuthAuditActorResolver actors) {
        this.publisher = publisher;
        this.actors = actors;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default ({@code REQUIRED}) propagation: joins the business transaction so the audit row
     * and the state change it describes commit together or not at all.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String aggregateType, String aggregateId, String eventType,
                       String beforeJson, String afterJson) {
        append(aggregateType, aggregateId, eventType, beforeJson, afterJson);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code REQUIRES_NEW}: see the class javadoc — a row recording a rejection must not be
     * rolled back by the rejection.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(String aggregateType, String aggregateId, String eventType,
                                String detailsJson) {
        // A rejection has no "before" state to snapshot: nothing changed. The details go in
        // after_jsonb so a single reporting query over after_jsonb covers both kinds of row.
        append(aggregateType, aggregateId, eventType, null, detailsJson);
    }

    @Override
    public String currentActor() {
        return actors.currentActor();
    }

    private void append(String aggregateType, String aggregateId, String eventType,
                        String beforeJson, String afterJson) {
        try {
            publisher.append(
                    aggregateType,
                    AuthAuditEvents.clamp(aggregateId),
                    actors.currentActor(),
                    actors.currentIp(),
                    eventType,
                    bytes(beforeJson),
                    bytes(afterJson),
                    // MICROS: recorded_at is inside the digest, so the in-memory value must equal
                    // what the TIMESTAMP column stores. (DbAuditPublisher truncates too; doing it
                    // here as well keeps the value we log identical to the value we sealed.)
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (RuntimeException e) {
            log.error("audit: FAILED to record {} on {}/{} — this event has no audit row. "
                            + "actor resolution or the audit tier is broken (gap T5-1)",
                    eventType, aggregateType, aggregateId, e);
        }
    }

    private static byte[] bytes(String json) {
        return json == null ? null : json.getBytes(StandardCharsets.UTF_8);
    }
}
