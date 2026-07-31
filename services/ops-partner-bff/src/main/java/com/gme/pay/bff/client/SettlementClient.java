package com.gme.pay.bff.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only view of settlement-reconciliation.
 *
 * <h2>GAP T4-5 — what changed and why</h2>
 * This client used to have exactly one upstream call, {@code GET /v1/settlements}, which
 * <b>recomputes</b> per-merchant figures from unbatched approved transactions for a single date and
 * reads neither {@code settlement_batches} nor {@code settlement_lines}. Four consequences, all of
 * them now gone:
 * <ul>
 *   <li>{@link #detail(String)} returned {@code null} unconditionally → every Admin drawer request
 *       404'd, and its javadoc said so;</li>
 *   <li>there was no date-range query, so {@link #recent} could only ever show the current
 *       settlement date;</li>
 *   <li>batch ids were <b>synthesised</b> as {@code merchantId-date-type} — ids that matched no
 *       persisted row, so nothing could be looked up by them;</li>
 *   <li>{@code status} was <b>hardcoded</b> to {@code COMPLETED}, a word absent from the upstream
 *       vocabulary entirely.</li>
 * </ul>
 * The upstream now exposes the persisted record ({@code /v1/settlements/batches},
 * {@code /v1/settlements/batches/{id}}, {@code /v1/settlements/statement}), so every id and status
 * here is real, and {@link SettlementBatchSummary#transmissionState()} carries whether the file was
 * ever actually sent — separately from the lifecycle status, because a batch can be RECONCILED
 * without GME having transmitted anything.
 */
public interface SettlementClient {

    /**
     * Returns the most recent settlement batches across all partners (Admin UI) or for one partner
     * (Portal UI), reading the persisted batches.
     *
     * @param partnerId optional — null means all partners
     * @param limit     maximum number of batches to return
     */
    List<SettlementBatchSummary> recent(String partnerId, int limit);

    /**
     * Fetches one settlement batch and its lines. Returns {@code null} when the batch is unknown —
     * the controller maps that to HTTP 404. Unlike before, {@code null} now means "no such batch",
     * not "no such capability".
     */
    SettlementBatchDetail detail(String batchId);

    /**
     * Persisted batches over a business-date window (T4-5). Either bound may be null; upstream
     * anchors the window and caps its width.
     *
     * @param partnerId optional partner/counterparty filter
     * @param limit     {@code 0} or less for every batch in the window
     */
    default List<SettlementBatchSummary> range(String partnerId, LocalDate from, LocalDate to, int limit) {
        return recent(partnerId, limit);
    }

    /**
     * The partner-facing settlement statement for one partner over a window (T4-5) — the settled
     * record, summed from the persisted settlement lines, not a recomputation.
     */
    default PartnerStatement statement(String partnerId, LocalDate from, LocalDate to, boolean includeLines) {
        throw new UnsupportedOperationException(
                "settlement statement is not implemented by " + getClass().getName());
    }

    /**
     * Whether settlement-reconciliation can transmit a file to the scheme at all. Never throws;
     * degrades to a "unknown" board rather than implying a channel exists.
     */
    default TransmissionChannel transmissionChannel() {
        return TransmissionChannel.unknown(
                "settlement-reconciliation did not report a transmission channel board");
    }

    // -------- Ops wave: recon exceptions gauge + operator rerun --------------
    //
    // Additive default methods (never break existing stubs / fakes). Both real
    // implementations (rest + stub) override.

    /**
     * Count of OPEN reconciliation exceptions (unmatched / breaks) for the
     * control-tower gauge. Routes to settlement-reconciliation's exceptions
     * endpoint. Never throws — degrades to {@code null} ("unknown") when the
     * upstream is unavailable so the control-tower section shows unknown, not 500.
     */
    default Integer openReconExceptions() {
        return null;
    }

    /**
     * Operator-triggered reconciliation rerun. Routes to settlement-reconciliation's
     * {@code POST /v1/settlements/recon/rerun} carrying an optional {@code date} +
     * operator {@code reason}. Returns the outcome; upstream 4xx propagates as
     * {@code ResponseStatusException} from the rest impl.
     */
    default ReconRerunResult rerunRecon(String date, String actor, String reason) {
        throw new UnsupportedOperationException(
                "rerunRecon is not implemented by " + getClass().getName());
    }

    /** Outcome of a reconciliation rerun. */
    record ReconRerunResult(String status, Integer matched, Integer unmatched, String detail) {}

    /**
     * Whether a settlement file can be transmitted, mirroring the upstream board.
     *
     * @param live            {@code true} only when upstream reports a real configured channel
     * @param reachableState  the most advanced transmission state a batch can reach
     * @param reason          why it cannot transmit; null when it can
     */
    record TransmissionChannel(boolean live, String reachableState, String reason) {

        /**
         * Upstream said nothing (old version, or unreachable). Never rendered as available: an absent
         * board means we do not know, which is not the same as "a channel exists".
         */
        public static TransmissionChannel unknown(String reason) {
            return new TransmissionChannel(false, com.gme.pay.bff.settlement.SettlementStatuses.UNKNOWN,
                    reason);
        }
    }

    /**
     * One persisted settlement batch.
     *
     * @param batchId           the REAL upstream batch id — never synthesised
     * @param partnerId         the batch's counterparty/partner as upstream reports it
     * @param settlementDate    business date
     * @param currency          settlement currency
     * @param amount            net settlement amount
     * @param status            REAL lifecycle status, normalised by
     *                          {@link com.gme.pay.bff.settlement.SettlementStatuses} — never hardcoded
     * @param transmissionState whether the file actually left; {@code UNKNOWN} when upstream is silent,
     *                          never a success
     * @param transmissionReason why it is in that transmission state
     * @param transmittedAt     ISO instant the file left, or null — null for every batch today
     */
    record SettlementBatchSummary(
            String batchId,
            String partnerId,
            LocalDate settlementDate,
            String currency,
            BigDecimal amount,
            String status,
            String transmissionState,
            String transmissionReason,
            String transmittedAt
    ) {
        /** {@code true} only when upstream said the file was transmitted. */
        public boolean transmitted() {
            return com.gme.pay.bff.settlement.SettlementStatuses.isTransmitted(transmissionState);
        }
    }

    /** One row of a settlement batch — a single scheme/transaction line. */
    record SettlementLine(
            String txnRef,
            BigDecimal amount,
            String currency,
            boolean matched
    ) {}

    /** Full settlement-batch view used by the Admin UI drawer. */
    record SettlementBatchDetail(
            SettlementBatchSummary batch,
            List<SettlementLine> lines,
            int matchedCount,
            int openCount
    ) {
        public SettlementBatchDetail(SettlementBatchSummary batch, List<SettlementLine> lines) {
            this(batch, lines,
                    (int) lines.stream().filter(SettlementLine::matched).count(),
                    (int) lines.stream().filter(l -> !l.matched()).count());
        }
    }

    /**
     * A partner's settlement statement over a window.
     *
     * @param entries               one row per batch the partner appears on
     * @param transmittedEntryCount how many of those were actually sent to the scheme — {@code 0}
     *                              today, stated rather than implied
     * @param transmissionChannel   the channel board, so a partner can see that nothing has been sent
     */
    record PartnerStatement(
            String partnerId,
            LocalDate from,
            LocalDate to,
            String currency,
            List<StatementEntry> entries,
            BigDecimal netSettlementAmount,
            BigDecimal paymentAmount,
            BigDecimal clawbackAmount,
            int lineCount,
            int openLineCount,
            int transmittedEntryCount,
            TransmissionChannel transmissionChannel
    ) {}

    /** One batch's contribution to a partner statement. */
    record StatementEntry(
            SettlementBatchSummary batch,
            BigDecimal netSettlementAmount,
            BigDecimal paymentAmount,
            BigDecimal clawbackAmount,
            int lineCount,
            int openLineCount,
            List<SettlementLine> lines
    ) {}
}
