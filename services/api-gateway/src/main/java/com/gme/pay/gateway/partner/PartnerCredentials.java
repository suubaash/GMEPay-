package com.gme.pay.gateway.partner;

import java.util.List;

/**
 * Immutable value object holding all credentials and policy config for one API partner.
 * Resolved by {@link PartnerCredentialService} on every request via X-API-Key lookup.
 */
public record PartnerCredentials(
        String partnerId,
        String apiKeyHash,
        /** Raw HMAC-SHA256 secret used to verify X-Signature. */
        String apiSecretHmacKey,
        /** CIDR ranges allowed to call the API; empty = no IP restriction. */
        List<String> ipCidrRanges,
        PartnerType type,
        int rateQuoteTtlSeconds) {

    public enum PartnerType {
        LOCAL,
        OVERSEAS
    }
}
