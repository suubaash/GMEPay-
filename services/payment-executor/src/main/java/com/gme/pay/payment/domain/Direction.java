package com.gme.pay.payment.domain;

/** Payment direction as per API-05 §4.3. */
public enum Direction {
    INBOUND,
    OUTBOUND,
    DOMESTIC,
    HUB
}
