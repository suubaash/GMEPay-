package com.gme.pay.bff.web.dto;

import com.gme.pay.bff.client.TransactionMgmtClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Full transaction view for the Admin/Portal transaction-detail drawer. Wraps
 * the read-side {@link TransactionMgmtClient.TransactionSummary} and adds the
 * scheme acknowledgement fields, prefund deduction, the booked settlement
 * amount, and the per-partner rounding mode + residual (see
 * {@code docs/MONEY_CONVENTION.md}).
 *
 * <p>Phase-1 stub controllers synthesize the additional fields from fixed
 * deltas of the summary — the wire shape is what matters for the UI here.
 */
public record TransactionDetail(
        TransactionMgmtClient.TransactionSummary summary,
        String schemeTxnRef,
        String schemeApprovalCode,
        BigDecimal prefundDeductedUsd,
        Instant approvedAt,
        BigDecimal bookedSettlementAmount,
        RoundingMode settlementRoundingMode,
        BigDecimal roundingResidual
) {}
