package com.gme.pay.scheme.zeropay.adapter.model;

/** Result of a cancellation request to the scheme. */
public record CancelResult(
        boolean success,
        String schemeResultCode,
        String cancellationRef
) {}
