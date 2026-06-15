package com.gme.pay.settlement.exception;

import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.recon.MatchStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for the exception API — GET /v1/settlement/exceptions.
 *
 * <p>All money fields are {@link BigDecimal} serialized as decimal strings on the wire
 * (MONEY_CONVENTION.md). Consumers MUST NOT cast to {@code double}.
 *
 * <p>Field names below are the canonical contract for the admin-ui front-end.
 * Do NOT rename without a coordinated UI change.
 */
public record ReconExceptionResponse(
        /** Database PK. */
        Long id,

        /** Settlement batch identifier, e.g. "ZP0062-20260615-001". */
        String batchId,

        /** Merchant ID as it appears in both GME internal data and the ZeroPay file. */
        String merchantId,

        /**
         * GME's expected settlement amount (KRW, integer scale).
         * Serialized as a decimal string, e.g. "34720".
         */
        BigDecimal gmeAmount,

        /**
         * ZeroPay-confirmed amount (KRW, integer scale). Null when {@code matchStatus}
         * is {@code MISSING_SCHEME} (ZeroPay had no entry for this merchant).
         */
        BigDecimal schemeAmount,

        /**
         * Absolute difference between {@code gmeAmount} and {@code schemeAmount} (KRW).
         * Zero when both sides agree (should not happen for exception rows, but included for completeness).
         */
        BigDecimal discrepancyAmount,

        /**
         * Recon match classification: DISCREPANCY, MISSING_SCHEME, or MISSING_INTERNAL.
         * MATCHED rows are never surfaced via this API.
         */
        MatchStatus matchStatus,

        /**
         * Ops lifecycle status: OPEN (new), RESOLVED, or RE_RUN.
         */
        ExceptionStatus exceptionStatus,

        /** Operator id (email) of the ops user who last acted on this exception. Null if untouched. */
        String operatorId,

        /** Free-text resolution note from ops. Null if not yet resolved. */
        String resolutionNote,

        /**
         * Structured action taken at resolution time.
         * Suggested: MANUAL_OVERRIDE, RESUBMIT, WAIVED. Null if not yet resolved.
         */
        String resolutionAction,

        /** UTC instant the exception was resolved. Null if not yet resolved. */
        Instant resolvedAt,

        /** UTC instant this exception row was created by the diff engine. */
        Instant createdAt
) {

    /** Map a JPA entity to the wire DTO. */
    public static ReconExceptionResponse from(ReconExceptionEntity e) {
        return new ReconExceptionResponse(
                e.getId(),
                e.getBatchId(),
                e.getMerchantId(),
                e.getGmeAmount(),
                e.getSchemeAmount(),
                e.getDiscrepancyAmount(),
                e.getMatchStatus(),
                e.getExceptionStatus(),
                e.getOperatorId(),
                e.getResolutionNote(),
                e.getResolutionAction(),
                e.getResolvedAt(),
                e.getCreatedAt());
    }
}
