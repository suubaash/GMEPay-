package com.gme.pay.scheme.zeropay.adapter.model;

import java.math.BigDecimal;

/** Request to authorise a CPM (Consumer-Presented Mode) payment. */
public record CpmAuthRequest(
        String merchantId,
        String qrCodeId,
        BigDecimal amountKrw,
        String partnerTxnRef,
        String idempotencyKey
) {}
