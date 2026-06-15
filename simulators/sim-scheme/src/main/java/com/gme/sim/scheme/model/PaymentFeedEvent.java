package com.gme.sim.scheme.model;

import java.math.BigDecimal;

/**
 * A single event in a merchant's payment-notification feed.
 * <p>
 * seq         – monotonically increasing per merchant, starting at 1.<br>
 * authId      – scheme authorization id (AUTH-...).<br>
 * schemeTxnRef – scheme transaction reference (TXN-...); null until CAPTURED.<br>
 * status      – one of "APPROVED", "CAPTURED", "REFUNDED".<br>
 * amount      – payment amount as BigDecimal (serialized as JSON string by Jackson).<br>
 * currency    – ISO 4217 currency code, e.g. "KRW".<br>
 * payerRef    – opaque payer identifier from the wallet.<br>
 * at          – KST ISO-8601 timestamp when the event was recorded.
 */
public record PaymentFeedEvent(
        long seq,
        String authId,
        String schemeTxnRef,   // null for APPROVED events
        String status,         // "APPROVED" | "CAPTURED" | "REFUNDED"
        BigDecimal amount,
        String currency,
        String payerRef,
        String at              // KST ISO-8601
) {}
