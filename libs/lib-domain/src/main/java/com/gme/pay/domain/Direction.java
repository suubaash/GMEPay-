package com.gme.pay.domain;

/** Per-transaction direction; part of the Rule key. */
public enum Direction {
    INBOUND,
    OUTBOUND,
    DOMESTIC,
    HUB,
    /** Korean wallet paying an overseas (cross-border) merchant QR — API-05 §4.3. */
    OVERSEAS
}
