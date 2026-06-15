package com.gme.pay.payment.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /v1/pay/{schemeTxnRef}/refund.
 *
 * <p>The {@code authId} field MUST carry the authorise-level authId (stored as
 * {@code schemeApprovalCode} at payment time). This is the ID that sim-scheme's
 * {@code POST /payments/{authId}/refund} endpoint requires.
 */
public record WalletRefundRequest(
        /**
         * Scheme authorise-level authId ({@code schemeApprovalCode} from the original payment).
         * Required for the refund call to sim-scheme.
         */
        @JsonProperty("authId")  String authId,
        /** Human-readable refund reason. Optional; defaults to "PARTNER_REFUND". */
        @JsonProperty("reason")  String reason
) {}
