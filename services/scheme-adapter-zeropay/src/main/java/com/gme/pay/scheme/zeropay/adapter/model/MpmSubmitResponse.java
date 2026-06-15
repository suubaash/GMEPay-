package com.gme.pay.scheme.zeropay.adapter.model;

/** Response from MPM payment submission. */
public record MpmSubmitResponse(
        String zeroPayTxnRef,
        String resultCode,
        String resultMessage,
        /** ZeroPay authorise-level authId; needed by the cancel/refund path. */
        String authId,
        /** Commit timestamp returned by sim-scheme (ISO-8601 string). */
        String committedAt
) {
    /** Backwards-compat factory for the 3-arg form (authId and committedAt both null). */
    public static MpmSubmitResponse of(String zeroPayTxnRef, String resultCode, String resultMessage) {
        return new MpmSubmitResponse(zeroPayTxnRef, resultCode, resultMessage, null, null);
    }
}
