package com.gme.pay.ratefx.issue;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
 */
@Service
public class RateSnapshotAdminService {

    private static final Set<String> ALLOWED_SOURCES = Set.of("MANUAL", "PARTNER");

    private final RateSnapshotRepository snapshots;
    private final Clock clock;

    public RateSnapshotAdminService(RateSnapshotRepository snapshots, Clock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
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
        RateSnapshotEntity entity = new RateSnapshotEntity(
                src.toLowerCase(Locale.ROOT) + "-" + ccy + "-" + UUID.randomUUID(),
                ccy, usdRate, src, effective, now);
        return snapshots.save(entity);
    }
}
