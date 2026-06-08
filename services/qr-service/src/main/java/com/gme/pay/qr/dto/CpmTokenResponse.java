package com.gme.pay.qr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for POST /v1/qr/cpm/generate (WBS 5.3-T03).
 *
 * <p>Amount fields ({@code prefundReservedUsd}) are represented as {@link String} to avoid
 * floating-point serialisation issues. {@code @JsonInclude(NON_NULL)} ensures OVERSEAS-only
 * fields are omitted for LOCAL partners.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CpmTokenResponse(
        String cpmTokenId,
        String prepareToken,
        String qrContent,
        /** ISO-8601 UTC timestamp, e.g. "2026-06-08T12:00:00Z". */
        String expiresAt,
        /** Decimal string, present only for OVERSEAS partners. */
        String prefundReservedUsd,
        String paymentId,
        String schemeId,
        String partnerTxnRef,
        /** ISO-8601 UTC timestamp. */
        String createdAt
) {}
