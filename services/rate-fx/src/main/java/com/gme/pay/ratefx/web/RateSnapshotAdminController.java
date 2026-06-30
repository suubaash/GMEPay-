package com.gme.pay.ratefx.web;

import com.gme.pay.ratefx.issue.RateSnapshotAdminService;
import com.gme.pay.ratefx.persistence.RateSnapshotEntity;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoint for manual treasury-rate overrides (RATE-04 §3.2 manual-override tail) and
 * seeding PARTNER quotes (WBS 4.6). {@code POST /v1/rates/snapshots} appends a new effective-dated
 * snapshot row; the latest one wins at resolution time.
 *
 * <p>Distinct from the stateless calculator {@link RateController} ({@code POST /v1/rates}). In
 * production this path is intended to sit behind the internal-auth gate (operator-only).
 */
@RestController
@RequestMapping("/v1/rates/snapshots")
public class RateSnapshotAdminController {

    private final RateSnapshotAdminService adminService;

    public RateSnapshotAdminController(RateSnapshotAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse record(@RequestBody SnapshotRequest req) {
        RateSnapshotEntity saved = adminService.record(
                req.currencyCode(), req.usdRate(), req.source(), req.effectiveAt());
        return new SnapshotResponse(saved.getSnapshotId(), saved.getCurrencyCode(),
                saved.getUsdRate(), saved.getSource(), saved.getEffectiveAt(), saved.getCapturedAt());
    }

    /**
     * @param currencyCode ISO-4217 (not USD)
     * @param usdRate      units of {@code currencyCode} per 1 USD; {@code > 0}
     * @param source       {@code MANUAL} or {@code PARTNER}
     * @param effectiveAt  when the rate becomes effective (nullable → now)
     */
    public record SnapshotRequest(String currencyCode, BigDecimal usdRate, String source,
                                  Instant effectiveAt) {
    }

    /** The persisted snapshot row echoed back for operator confirmation/audit. */
    public record SnapshotResponse(String snapshotId, String currencyCode, BigDecimal usdRate,
                                   String source, Instant effectiveAt, Instant capturedAt) {
    }
}
