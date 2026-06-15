package com.gme.pay.payment.domain.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Interface to the Scheme Adapter Layer (e.g. scheme-adapter-zeropay).
 * Implementations send to ZeroPay over mTLS; tests use hand-written fakes.
 */
public interface SchemeClient {

    /**
     * Submits an MPM payment to the scheme for real-time approval.
     *
     * @param request the scheme submit request
     * @return approval result from the scheme
     * @throws SchemeDeclinedException if the scheme synchronously declines
     * @throws SchemeTimeoutException  if the scheme does not respond within SLA
     */
    MpmSubmitResponse submitMpm(MpmSubmitRequest request);

    /**
     * Cancels a previously approved payment at the scheme.
     *
     * @param schemeTxnRef the scheme's own transaction reference
     * @param reason       cancellation reason text
     */
    void cancelPayment(String schemeTxnRef, String reason);

    /**
     * Submits a CPM payment (customer-presented mode) to the scheme.
     *
     * @param request the CPM submit request
     * @return approval result
     */
    CpmSubmitResponse submitCpm(CpmSubmitRequest request);

    // ---- request/response value objects ----

    record MpmSubmitRequest(
            String txnRef,
            String merchantId,
            BigDecimal payoutAmount,
            String payoutCurrency,
            String schemeId,
            /** Raw EMVCo QR payload — required for ZeroPay MPM authorize call. */
            String qrPayload
    ) {
        /**
         * Backwards-compatible 5-arg factory for callers that do not supply a QR payload
         * (OVERSEAS path, cancellation path, existing tests).
         */
        public static MpmSubmitRequest of(String txnRef, String merchantId,
                                          BigDecimal payoutAmount, String payoutCurrency,
                                          String schemeId) {
            return new MpmSubmitRequest(txnRef, merchantId, payoutAmount, payoutCurrency, schemeId, null);
        }
    }

    record MpmSubmitResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt
    ) {}

    record CpmSubmitRequest(
            String txnRef,
            String qrToken,
            BigDecimal payoutAmount,
            String payoutCurrency,
            String schemeId
    ) {}

    record CpmSubmitResponse(
            String schemeApprovalCode,
            String schemeTxnRef,
            Instant approvedAt
    ) {}
}

// Pull exception imports to the right package so they compile with the interface
