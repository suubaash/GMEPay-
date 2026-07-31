package com.gme.pay.ratefx.issue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.audit.RateAuditor;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Operator entry point for inserting treasury cost-rate snapshots by hand (RATE-04 §3.2, gap B4
 * manual-override tail). The XE scheduler upserts {@code LIVE} rows automatically; this service lets
 * operations record a {@code MANUAL} override (overrides LIVE for a contract-locked partner) or seed a
 * {@code PARTNER} rate (the in-process Partner B quote feed, WBS 4.6) without the scheduler.
 *
 * <p>Each call appends a NEW effective-dated row (snapshots are immutable, never updated in place);
 * resolution always reads the most recent effective row, so the latest override wins. {@code IDENTITY}
 * is never stored (a USD leg is priced at 1.0 structurally), and {@code LIVE} is reserved for the
 * automated feed — both are rejected here.
 *
 * <h2>Audit (gap T5-1 / CISO §9)</h2>
 *
 * <p>This is the class behind the finding "FX rate change — <b>NO</b>: {@code rate_snapshots} permits
 * {@code source='MANUAL'} with no actor column". Every successful {@link #record} now also writes one
 * hash-chained {@code audit_log} row naming <b>who</b> set the rate, what it was before, and what it
 * became — see {@link RateAuditor}. The {@code rate_snapshots} row itself is unchanged: it is
 * immutable, effective-dated and read on the quote hot path, and bolting an actor column onto it would
 * still leave the before/after and the tamper-evidence unrecorded.
 *
 * <p>A rejected call (USD, non-positive rate, {@code LIVE}) writes nothing and is audited nowhere,
 * because nothing changed. Attempted-and-refused writes are an access-control signal rather than a
 * rate-change signal and belong with the internal-auth gate's own logging.
 */
@Service
public class RateSnapshotAdminService {

    private static final Set<String> ALLOWED_SOURCES = Set.of("MANUAL", "PARTNER");

    private final RateSnapshotRepository snapshots;
    private final Clock clock;
    private final RateAuditor audit;

    public RateSnapshotAdminService(RateSnapshotRepository snapshots, Clock clock, RateAuditor audit) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * Append an operator override / PARTNER snapshot. {@code effectiveAt} defaults to now when null.
     *
     * @param currencyCode ISO-4217 (not USD — USD legs price at identity)
     * @param usdRate      units of {@code currencyCode} per 1 USD; must be {@code > 0}
     * @param source       {@code MANUAL} or {@code PARTNER}
     * @param effectiveAt  when the rate becomes effective (nullable → now)
     */
    public RateSnapshotEntity record(String currencyCode, BigDecimal usdRate, String source,
                                     Instant effectiveAt) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "currencyCode is required");
        }
        String ccy = currencyCode.trim().toUpperCase(Locale.ROOT);
        if ("USD".equals(ccy)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "USD is an identity leg (rate 1.0) — no snapshot needed");
        }
        if (usdRate == null || usdRate.signum() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "usdRate must be positive");
        }
        String src = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SOURCES.contains(src)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "source must be one of " + ALLOWED_SOURCES + " (LIVE is set by the XE feed)");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant effective = (effectiveAt == null ? now : effectiveAt).truncatedTo(ChronoUnit.MICROS);
        // Read the rate this write displaces BEFORE saving, so the audit row can state what actually
        // changed. "USD/MNT was moved from 3450 to 3900 by alice@gme.com" is a finding; "USD/MNT was
        // set to 3900" is a fact with no context.
        RateAuditor.RateState before = stateInForce(ccy, effective);
        RateSnapshotEntity entity = new RateSnapshotEntity(
                src.toLowerCase(Locale.ROOT) + "-" + ccy + "-" + UUID.randomUUID(),
                ccy, usdRate, src, effective, now);
        RateSnapshotEntity saved = snapshots.save(entity);
        audit.rateWritten(ccy, before, stateOf(saved),
                "MANUAL".equals(src)
                        ? "operator treasury-rate override via POST /v1/rates/snapshots"
                        : "PARTNER rate seeded via POST /v1/rates/snapshots",
                null);
        return saved;
    }

    /** The rate in force for {@code ccy} at {@code asOf}, or {@link RateAuditor.RateState#none()}. */
    private RateAuditor.RateState stateInForce(String ccy, Instant asOf) {
        Optional<RateSnapshotEntity> current = snapshots
                .findFirstByCurrencyCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescCapturedAtDesc(
                        ccy, asOf);
        return current.map(RateSnapshotAdminService::stateOf).orElseGet(RateAuditor.RateState::none);
    }

    private static RateAuditor.RateState stateOf(RateSnapshotEntity e) {
        return new RateAuditor.RateState(e.getUsdRate(), e.getSource(), e.getSnapshotId(),
                e.getEffectiveAt());
    }
}
