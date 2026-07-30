package com.gme.pay.settlement.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.settlement.transmission.SettlementTransmissionChannelStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The <b>partner-facing settlement statement</b> (GAP T4-5) — one merchant's settled position over a
 * date window, read entirely from the persisted {@code settlement_batches} / {@code settlement_lines}.
 *
 * <h2>What makes this a statement and the old endpoint not one</h2>
 * {@code GET /v1/settlements} recomputes per-merchant figures from <em>unbatched approved
 * transactions</em> for a single date. That is a projection of what a batch would look like if one
 * were generated — useful, but it is not a record of anything, it cannot cover a period, and it
 * disagrees with the books the moment a batch is actually booked (booking applies the partner's
 * rounding mode and nets cross-date refund claw-backs). A statement has to be the settled record, so
 * every figure here is summed from the persisted lines of real batches.
 *
 * <h2>Transmission honesty is part of the statement, not a footnote</h2>
 * {@link #transmissionChannel} is carried on the statement itself and every {@link #entries} row
 * carries its batch's own transmission state. A partner reading this must be able to tell "GMEPay+
 * booked and reconciled this" from "GMEPay+ sent the instruction to the scheme" — today only the
 * former is true, for every row, because no transmission channel is configured.
 *
 * @param merchantId          the merchant the statement is for
 * @param from                window start (inclusive)
 * @param to                  window end (inclusive)
 * @param currency            settlement currency of the entries; null when the window is empty
 * @param entries             one row per batch this merchant appears on, newest business date first
 * @param netSettlementAmount Σ signed line amounts across the window — payments less claw-backs
 * @param paymentAmount       Σ positive line amounts (credited)
 * @param clawbackAmount      Σ absolute value of negative line amounts (refunds netted back out)
 * @param lineCount           total settled lines in the window
 * @param openLineCount       lines not yet reconciled against the scheme's confirmation
 * @param transmittedEntryCount how many of the entries were actually transmitted to the scheme —
 *                            {@code 0} in every environment today, and stated rather than implied
 * @param transmissionChannel the channel board: whether transmission is possible at all, and why not
 */
public record PartnerSettlementStatementResponse(
        String merchantId,
        LocalDate from,
        LocalDate to,
        String currency,
        List<Entry> entries,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netSettlementAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paymentAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal clawbackAmount,
        int lineCount,
        int openLineCount,
        int transmittedEntryCount,
        SettlementTransmissionChannelStatus transmissionChannel) {

    /**
     * One batch's contribution to the statement.
     *
     * @param batch this merchant's share of the batch — the batch metadata plus the merchant's own
     *              lines and their net, never the whole batch's net (which spans every merchant)
     * @param lines the merchant's settled lines on that batch
     */
    public record Entry(
            SettlementBatchSummaryResponse batch,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netSettlementAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paymentAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal clawbackAmount,
            int lineCount,
            int openLineCount,
            List<SettlementBatchLineResponse> lines) {}
}
