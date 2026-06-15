package com.gme.pay.bff.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gme.pay.bff.client.TransactionMgmtClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Full transaction view for the Admin/Portal transaction-detail drawer (UC-10-03).
 * Wraps the read-side {@link TransactionMgmtClient.TransactionSummary} and adds the
 * scheme acknowledgement fields, prefund deduction, the booked settlement
 * amount, the per-partner rounding mode + residual (see {@code docs/MONEY_CONVENTION.md}),
 * and the UC-10-03 merchant and status-history fields.
 *
 * <p>ADDITIVE ONLY: original 8 fields retained exactly as before.
 *
 * <p>UC-10-03 additive fields:
 * <ul>
 *   <li>{@code merchantId}     – merchant terminal/store id from the QR scheme. Null until scheme-adapter wires it.</li>
 *   <li>{@code merchantName}   – merchant display name. Null until scheme-adapter wires it.</li>
 *   <li>{@code statusHistory}  – ordered list of status transitions, oldest first. Null until tracking table is wired.</li>
 * </ul>
 *
 * <p>Money fields are BigDecimal serialized as decimal strings ({@code @JsonFormat(STRING)}).
 * The UI MUST NOT cast these to JS Number.
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps the payload compact for existing consumers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionDetail(
        // --- original fields (DO NOT RENAME / REMOVE) ---
        TransactionMgmtClient.TransactionSummary summary,
        String schemeTxnRef,
        String schemeApprovalCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal prefundDeductedUsd,
        Instant approvedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal bookedSettlementAmount,
        RoundingMode settlementRoundingMode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal roundingResidual,
        // --- UC-10-03 additive fields ---
        /** Merchant terminal/store identifier from the QR scheme. TODO: populate from scheme-adapter. */
        String merchantId,
        /** Merchant display name from the QR scheme. TODO: populate from scheme-adapter. */
        String merchantName,
        /** Ordered status-transition history, oldest first. TODO: wire status-history tracking. */
        List<TransactionMgmtClient.StatusEntry> statusHistory
) {
    /**
     * Convenience factory preserving the original 8-arg wire shape.
     * UC-10-03 additive fields default to {@code null}.
     */
    public static TransactionDetail of(
            TransactionMgmtClient.TransactionSummary summary,
            String schemeTxnRef,
            String schemeApprovalCode,
            BigDecimal prefundDeductedUsd,
            Instant approvedAt,
            BigDecimal bookedSettlementAmount,
            RoundingMode settlementRoundingMode,
            BigDecimal roundingResidual) {
        return new TransactionDetail(
                summary, schemeTxnRef, schemeApprovalCode,
                prefundDeductedUsd, approvedAt,
                bookedSettlementAmount, settlementRoundingMode, roundingResidual,
                null, null, null);
    }
}
