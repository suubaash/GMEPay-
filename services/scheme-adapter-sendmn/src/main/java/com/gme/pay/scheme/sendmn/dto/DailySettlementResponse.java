package com.gme.pay.scheme.sendmn.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only projection of what THIS adapter recorded SendMN as having confirmed for one
 * settlement date — the scheme leg of settlement-reconciliation's SENDMN three-way tie-out
 * (GAP T2-2).
 *
 * <p>Each row is one {@code smn_payments} attempt in status {@code APPROVED}: the MNT the
 * merchant was paid, the SendMN-<b>registered</b> buy rate the Confirm was computed against
 * ({@code fxUsdBuyRate}, snapshotted from {@code smn_fx_rates} at Confirm time) and the USD
 * {@code settlementAmount} = MNT / rate that SendMN re-verifies (error 307). That USD figure is
 * what GME owes SendMN; the hub separately deducted USD from the partner float on a DIFFERENT
 * basis (live USD/KRW, or its fallback), and reconciling the two is the whole point of the feed.
 *
 * <p><b>This is not a partner settlement file.</b> SendMN's own recon file format is an open
 * external question (plan gate O4); this endpoint exposes only GME's own adapter-side record so
 * the internal tie-out can run today without it.
 *
 * <p>Money fields serialize as decimal strings per {@code docs/MONEY_CONVENTION.md}.
 *
 * @param date                  the settlement (KST) date queried, ISO-8601
 * @param status                the {@code smn_payments} status filtered on (always APPROVED today)
 * @param localCurCode          local (payout) currency of the corridor — MNT
 * @param settlementCurCode     settlement currency owed to SendMN — USD
 * @param latestRegisteredRate  the newest rate SendMN has registered for the pair (context only;
 *                              each row carries the rate its own Confirm actually used). Null when
 *                              SendMN has registered no rate yet.
 * @param count                 number of rows
 * @param rows                  the confirmed payments
 */
public record DailySettlementResponse(
        String date,
        String status,
        String localCurCode,
        String settlementCurCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal latestRegisteredRate,
        int count,
        List<Row> rows) {

    /**
     * One confirmed SendMN payment as this adapter recorded it.
     *
     * @param hubReference       payment-executor's stable reference (the join key for the tie-out —
     *                           the same value the hub used for the prefunding deduct). Null on rows
     *                           written before V002.
     * @param txTokenNo          SendMN idempotency key for the attempt
     * @param merchantId         merchant GUID resolved at VerifyQr
     * @param localCurCode       MNT
     * @param localAmount        MNT paid to the merchant
     * @param fxTickerNo         id of the registered rate used at Confirm
     * @param fxUsdBuyRate       the registered MNT-per-USD buy rate used at Confirm
     * @param settlementCurCode  USD
     * @param settlementAmount   USD owed to SendMN = localAmount / fxUsdBuyRate (scale 4)
     * @param status             canonical adapter status (APPROVED for settlement rows)
     * @param paymentNo          SendMN tracking number
     * @param paymentReceiptNo   SendMN control number
     * @param createdAt          instant the attempt row was created (drives the date window)
     * @param updatedAt          instant the row last changed status
     */
    public record Row(
            String hubReference,
            String txTokenNo,
            String merchantId,
            String localCurCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal localAmount,
            String fxTickerNo,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fxUsdBuyRate,
            String settlementCurCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal settlementAmount,
            String status,
            String paymentNo,
            String paymentReceiptNo,
            Instant createdAt,
            Instant updatedAt) {}
}
