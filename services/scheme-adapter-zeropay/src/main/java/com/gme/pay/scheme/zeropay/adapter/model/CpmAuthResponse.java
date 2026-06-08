package com.gme.pay.scheme.zeropay.adapter.model;

/** Response from CPM authorisation. */
public record CpmAuthResponse(
        String approvalCode,
        String zeroPayTxnRef,
        String resultCode,
        String resultMessage
) {}
