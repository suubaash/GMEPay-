package com.gme.pay.scheme.zeropay.adapter.model;

/** Response from MPM payment submission. */
public record MpmSubmitResponse(
        String zeroPayTxnRef,
        String resultCode,
        String resultMessage
) {}
