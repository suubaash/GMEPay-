package com.gme.pay.scheme.nepal.dto;

/**
 * Request for POST /internal/scheme/nepal/submit — authorize+commit in one shot.
 *
 * @param qs          the raw scanned QR string
 * @param amountPaisa payout amount in paisa (1 NPR = 100 paisa)
 * @param reference   globally-unique partner reference (idempotency key; duplicates rejected)
 * @param mobile      optional extension mobile
 * @param purpose     optional purpose (defaults "Remittance")
 * @param remarks     optional free-text remarks
 */
public record SubmitRequest(
        String qs,
        long amountPaisa,
        String reference,
        String mobile,
        String purpose,
        String remarks
) {}
