package com.gme.pay.prefunding.aml;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a partner's cumulative-usage ledger as AML monitoring EVIDENCE over a window of KST days —
 * gap T5-3.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code cumulative_usage_ledger} (V006) is the platform's only append-only record of per-partner
 * transaction volume and velocity, and it was reachable through exactly one shape of question: the
 * single-period sums the authorize path compares against a cap, under a row lock, on the hot path.
 * There was no way to ask it the question monitoring and investigation actually ask — <i>what did
 * this partner do, day by day, across this period, and what limits was it operating under</i> —
 * short of a direct database session. This service is that read, and it is the whole of what T5-3
 * closes on the query side.
 *
 * <h2>It surfaces evidence; it is not an AML control</h2>
 *
 * <p>Everything returned here is a reproducible aggregate of rows that already existed. This class
 * applies no threshold, forms no opinion, and flags nothing. The partner's configured caps ride
 * along as CONTEXT — they tell a reader what the partner was allowed to do, which is needed to
 * interpret what it did — and are explicitly not evaluated here. Whether any observed figure warrants
 * an alert is decided by operator-configured rules in {@link AmlMonitoringEvaluator}, and those rules
 * ship EMPTY (see {@link AmlMonitoringRules}).
 *
 * <h2>The window is bounded on purpose</h2>
 *
 * <p>An unbounded date range on an append-only ledger is a full-table scan wearing a query's clothes.
 * The span is capped at {@code gmepay.aml.monitoring.max-window-days} (default
 * {@value AmlMonitoringRules#DEFAULT_MAX_WINDOW_DAYS}) and an inverted range is a 400 rather than an
 * empty result, because an empty result reads to a reviewer exactly like "this partner did nothing".
 *
 * <p>Read-only ({@code @Transactional(readOnly = true)}): nothing on this path writes, and the
 * ledger it reads is append-only, so no lock is taken and the authorize path is not contended.
 */
@Service
public class AmlMonitoringService {

    /**
     * Strict {@code yyyy-MM-dd}. {@link ResolverStyle#STRICT} with the ISO week-based year field set
     * would reject valid dates, so the pattern uses {@code uuuu} — the proleptic year — which is what
     * strict resolution requires. The point of strict parsing is that {@code 2026-02-30} is rejected
     * rather than smeared to the 1st of March: a window bound that silently became a different day
     * would produce evidence for a period nobody asked about.
     */
    private static final DateTimeFormatter DAILY_KEY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final CumulativeUsageLedgerRepository ledger;
    private final PartnerBalanceRepository balances;
    private final AmlMonitoringRules rules;

    public AmlMonitoringService(CumulativeUsageLedgerRepository ledger,
                                PartnerBalanceRepository balances,
                                AmlMonitoringRules rules) {
        this.ledger = ledger;
        this.balances = balances;
        this.rules = rules;
    }

    /**
     * Per-KST-day usage plus window totals plus the partner's configured caps, for the INCLUSIVE
     * window {@code [fromDailyKey, toDailyKey]}.
     *
     * <p>Both bounds are inclusive (not the half-open {@code [from, to)} the instant-based movements
     * feed uses) because these are DAY KEYS, not instants: the ledger stores a pre-computed KST day
     * string, so "2026-07-01 to 2026-07-07" means those seven days and there is no boundary instant
     * to double-count.
     *
     * @throws ApiException {@code VALIDATION_ERROR} when a bound is absent or not a real
     *         {@code yyyy-MM-dd} date, when the range is inverted, when the span exceeds
     *         {@code gmepay.aml.monitoring.max-window-days}, or when the partner is unknown
     */
    @Transactional(readOnly = true)
    public AmlWindowEvidence evidence(String partnerId, String fromDailyKey, String toDailyKey) {
        if (partnerId == null || partnerId.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerId is required");
        }
        LocalDate from = parseDailyKey(fromDailyKey, "from");
        LocalDate to = parseDailyKey(toDailyKey, "to");
        if (to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "to (" + to + ") must not be before from (" + from + ") — the window is "
                            + "inclusive on both ends, so an inverted range can never match and "
                            + "would be indistinguishable from a partner with no activity");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        int max = rules.getMaxWindowDays();
        if (span > max) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "window of " + span + " days exceeds the maximum of " + max
                            + " (gmepay.aml.monitoring.max-window-days) — read it in consecutive "
                            + "windows rather than in one scan of the whole ledger");
        }

        // An unknown partner is an error, not an empty window. A fabricated empty result would read
        // to a reviewer as "this partner transacted nothing in this period", which is a materially
        // different — and false — statement from "there is no such partner".
        PartnerBalanceEntity partner = balances.findById(partnerId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR,
                        "unknown partnerId " + partnerId));

        List<AmlWindowEvidence.DayUsage> days =
                ledger.dailyUsageWindow(partnerId, from.toString(), to.toString()).stream()
                        .map(r -> new AmlWindowEvidence.DayUsage(r.getDailyKey(), r.getNetUsd(),
                                r.getNetTxnCount(), r.getChargeCount()))
                        .toList();

        AmlWindowEvidence.ConfiguredCaps caps = new AmlWindowEvidence.ConfiguredCaps(
                partner.getAmlDailyCapUsd(), partner.getAmlMonthlyCapUsd(),
                partner.getAmlAnnualCapUsd(), partner.getAmlDailyTxnCountCap());

        return AmlWindowEvidence.of(partnerId, from.toString(), to.toString(), days, caps);
    }

    /**
     * Strict {@code yyyy-MM-dd} parsing. A malformed bound is a 400 naming the offending value —
     * never a defaulted or best-effort date, because evidence produced for a window other than the
     * one requested is worse than no evidence.
     */
    private static LocalDate parseDailyKey(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    field + " is required (a KST day key, yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(raw.trim(), DAILY_KEY);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, field + " ('" + raw
                    + "') is not a yyyy-MM-dd KST day key, e.g. 2026-07-28");
        }
    }
}
