package com.gme.pay.ratefx.audit;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.DbAuditPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code audit_log} row for every treasury-rate mutation — the closure of gap T5-1 /
 * CISO §9 "FX rate change — <b>NO</b>: {@code rate_snapshots} permits {@code source='MANUAL'} with no
 * actor column".
 *
 * <h2>What was missing, precisely</h2>
 *
 * <p>{@code rate_snapshots} (V001) stores {@code (snapshot_id, currency_code, usd_rate, source,
 * effective_at, captured_at)} and its CHECK constraint explicitly admits {@code source = 'MANUAL'} —
 * a rate a human typed in. There is nowhere in the row to record which human, from where, or why. And
 * because resolution reads the most recent effective snapshot, one MANUAL row silently re-prices
 * every subsequent quote and payment in that currency. That is a pricing decision with direct money
 * consequences and it had no attributable author.
 *
 * <h2>Chain: one per currency, not one per snapshot</h2>
 *
 * <p>{@code aggregate_id} is the <b>currency code</b> ({@code MNT}, {@code KRW} — equivalently the
 * USD/&lt;ccy&gt; pair, since every snapshot is quoted per 1 USD). Keying on {@code snapshot_id}
 * instead would give every write its own chain of length one, which proves nothing about the
 * <i>sequence</i> of rates: an inserted or removed rate between two others would be undetectable.
 * Keying on the currency makes the chain the pricing history of that pair, which is what an
 * investigator reads and what a gap in the sequence must be visible against.
 *
 * <h2>Manual vs fetched, at a glance</h2>
 *
 * <p>Each source gets its own {@code event_type} <b>and</b> a distinct actor shape, so the two
 * questions an auditor asks are answerable without parsing a payload:
 *
 * <table border="1">
 *   <caption>Rate write attribution</caption>
 *   <tr><th>Source</th><th>event_type</th><th>actor_id</th></tr>
 *   <tr><td>{@code MANUAL}</td><td>{@link #RATE_SNAPSHOT_MANUAL_OVERRIDE}</td>
 *       <td>the operator (or {@code svc:} / {@code unverified:} / {@code unattributed} if that is all
 *           that was proven)</td></tr>
 *   <tr><td>{@code PARTNER}</td><td>{@link #RATE_SNAPSHOT_PARTNER_RECORDED}</td>
 *       <td>whoever seeded it, same vocabulary</td></tr>
 *   <tr><td>{@code LIVE}</td><td>{@link #RATE_SNAPSHOT_LIVE_FETCHED}</td>
 *       <td>{@link #SYSTEM_XE_FETCH_SCHEDULER} — a named platform principal, never the bare
 *           {@code "system"} literal</td></tr>
 * </table>
 *
 * <h2>Transaction participation</h2>
 *
 * <p>{@link DbAuditPublisher#append} runs on the caller's transaction, so the audit row commits if and
 * only if the snapshot it describes commits (ADR-007).
 */
@Component
public class RateAuditor {

    private static final Logger log = LoggerFactory.getLogger(RateAuditor.class);

    /** The only aggregate type rate-fx audits. {@code aggregate_id} = ISO-4217 currency code. */
    public static final String AGG_RATE_SNAPSHOT = "rate_snapshot";

    /** A human-entered override. This is the specific CISO finding. */
    public static final String RATE_SNAPSHOT_MANUAL_OVERRIDE = "RATE_SNAPSHOT_MANUAL_OVERRIDE";

    /** A partner-fed rate seeded through the same admin surface (WBS 4.6). */
    public static final String RATE_SNAPSHOT_PARTNER_RECORDED = "RATE_SNAPSHOT_PARTNER_RECORDED";

    /** A rate pulled by the automated provider poll. */
    public static final String RATE_SNAPSHOT_LIVE_FETCHED = "RATE_SNAPSHOT_LIVE_FETCHED";

    /**
     * The automated XE / provider poll. A genuine platform action with no human actor, named so a
     * fetched rate is distinguishable from a manual one — and from a lost identity — at a glance.
     */
    public static final String SYSTEM_XE_FETCH_SCHEDULER = AuditActors.system("xe-rate-fetch-scheduler");

    private final DbAuditPublisher publisher;
    private final AuditActorResolver actors;

    public RateAuditor(DbAuditPublisher publisher, AuditActorResolver actors) {
        this.publisher = publisher;
        this.actors = actors;
    }

    /**
     * The rate in force for a currency at one instant — what {@code before}/{@code after} compare.
     *
     * @param usdRate    units of the currency per 1 USD; {@code null} when no snapshot existed yet
     * @param source     which feed produced it; {@code null} when none existed
     * @param snapshotId the {@code rate_snapshots} primary key, so the audit row cites the exact
     *                   snapshot rather than just a number
     */
    public record RateState(BigDecimal usdRate, String source, String snapshotId,
                            Instant effectiveAt) {

        /** The position of a currency that has never been priced. */
        public static RateState none() {
            return new RateState(null, null, null, null);
        }
    }

    /**
     * Audit one rate write.
     *
     * @param currencyCode the ISO-4217 code, which is also the chain key
     * @param before       the rate previously in force (use {@link RateState#none()} if there was none)
     * @param after        the rate just written
     * @param reason       why, where the API carries one; {@code null} otherwise. Emitted as an
     *                     explicit JSON {@code null} rather than omitted — "no reason was supplied"
     *                     and "the writer forgot the field" must not encode identically
     * @param actorId      who acted; {@code null} to take the actor of the request being served
     */
    public void rateWritten(String currencyCode, RateState before, RateState after, String reason,
                            String actorId) {
        byte[] beforeJson = rate(before).bytes();
        byte[] afterJson = rate(after).str("reason", reason).bytes();
        append(chainKey(currencyCode), eventTypeFor(after.source()), beforeJson, afterJson, actorId);
    }

    /**
     * The {@code event_type} for a written source. An unrecognised source still produces a row —
     * dropping the audit because the vocabulary grew would be the worst possible response — but it is
     * spelled distinctly so it cannot be mistaken for a fetched rate.
     */
    static String eventTypeFor(String source) {
        String s = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "MANUAL" -> RATE_SNAPSHOT_MANUAL_OVERRIDE;
            case "PARTNER" -> RATE_SNAPSHOT_PARTNER_RECORDED;
            case "LIVE" -> RATE_SNAPSHOT_LIVE_FETCHED;
            default -> "RATE_SNAPSHOT_WRITTEN_SOURCE_" + (s.isEmpty() ? "UNKNOWN" : s);
        };
    }

    /** {@code aggregate_id} is {@code VARCHAR(64)}; a currency code cannot approach that, but clamp anyway. */
    private static String chainKey(String currencyCode) {
        String c = currencyCode == null ? "" : currencyCode.trim().toUpperCase(Locale.ROOT);
        if (c.isEmpty()) {
            // Never write a blank aggregate_id (the column is NOT NULL and a blank chain key would
            // silently merge every unidentified write into one chain).
            return "UNKNOWN";
        }
        return c.length() <= 64 ? c : c.substring(0, 64);
    }

    /** Fixed-order rate position — see {@link CanonicalJson} for why the order is pinned by hand. */
    private static CanonicalJson rate(RateState s) {
        RateState v = s == null ? RateState.none() : s;
        return CanonicalJson.object()
                .money("usdRate", v.usdRate())
                .str("source", v.source())
                .str("snapshotId", v.snapshotId())
                .at("effectiveAt", v.effectiveAt());
    }

    private void append(String currencyCode, String eventType, byte[] beforeJson, byte[] afterJson,
                        String actorId) {
        String actor = actorId != null ? actorId : actors.currentActor();
        String ip = actors.currentActorIp();
        try {
            publisher.append(AGG_RATE_SNAPSHOT, currencyCode, actor, ip, eventType,
                    beforeJson, afterJson, Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (IllegalArgumentException e) {
            // An unusable actorId is a caller bug, and DbAuditPublisher deliberately does not swallow
            // it. Re-throwing would fail the rate write; instead the row is written UNATTRIBUTED
            // (never dropped — a rate change with no audit row is the failure mode this gap exists to
            // remove) and the bug is logged loudly enough to find.
            log.error("audit: refusing actorId '{}' for {}/{} {} — writing the row as {} instead. "
                            + "This is a caller bug: use AuditActors.attested/system/service/unverified.",
                    actor, AGG_RATE_SNAPSHOT, currencyCode, eventType, AuditActors.UNATTRIBUTED, e);
            publisher.append(AGG_RATE_SNAPSHOT, currencyCode, AuditActors.UNATTRIBUTED, ip, eventType,
                    beforeJson, afterJson, Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
