package com.gme.pay.scheme.zeropay.adapter.model;

import java.time.Instant;

/** One-time token for a prepared CPM payment, valid until {@code expiresAt}. */
public record PrepareToken(
        String tokenId,
        String merchantId,
        Instant expiresAt
) {}
