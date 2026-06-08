package com.gme.pay.scheme.zeropay.adapter.model;

/** Generic result from a scheme commit or real-time operation. */
public record SchemeResult(
        boolean success,
        String schemeResultCode,
        String schemeResultMessage,
        String zeroPayTxnRef
) {}
