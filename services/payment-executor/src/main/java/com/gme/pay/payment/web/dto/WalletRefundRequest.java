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
        @JsonProperty("reason")  String reason,
        /**
         * T2-7 (optional): scheme CODE the original wallet payment was executed on — {@code "SENDMN"}
         * for the KRW→MNT corridor, {@code "NEPAL"} for Fonepay, absent/{@code "ZEROPAY"} for the
         * domestic path. Without it the refund routes to the ZeroPay adapter (legacy behaviour), which
         * is wrong for a cross-border payment; with it a single-shot scheme answers
         * {@code SCHEME_OPERATION_UNSUPPORTED} instead of a ZeroPay decline.
         */
        @JsonProperty("schemeId") String schemeId
) {

    /** Back-compatible 2-arg form; {@code schemeId} defaults to null (ZeroPay default routing). */
    public WalletRefundRequest(String authId, String reason) {
        this(authId, reason, null);
    }
}
