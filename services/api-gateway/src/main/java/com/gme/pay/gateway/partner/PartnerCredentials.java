package com.gme.pay.gateway.partner;

import java.util.List;

/**
 * Immutable value object holding all credentials and policy config for one API partner.
 * Resolved by {@link PartnerCredentialService} on every request via X-API-Key lookup.
 *
 * <p>mTLS: when {@code security.gateway.mtls.enabled=true}, Nginx terminates the TLS
 * handshake and forwards the verified client certificate as the
 * {@code X-Client-Cert-Fingerprint} header (SHA-256 hex, lower-case). The
 * {@link com.gme.pay.gateway.filter.MtlsFingerprintFilter} compares that header value
 * against {@code mtlsCertFingerprint} stored here. A {@code null} fingerprint means the
 * partner has not registered a certificate; the filter rejects such requests with 401
 * when mTLS mode is enabled.
 */
public record PartnerCredentials(
        String partnerId,
        String apiKeyHash,
        /** Raw HMAC-SHA256 secret used to verify X-Signature. */
        String apiSecretHmacKey,
        /** CIDR ranges allowed to call the API; empty = no IP restriction. */
        List<String> ipCidrRanges,
        PartnerType type,
        int rateQuoteTtlSeconds,
        /**
         * SHA-256 fingerprint (lower-case hex, no colons) of the partner's mTLS client
         * certificate as stored in {@code partner_mtls_cert}. {@code null} when the partner
         * has not registered a certificate.
         */
        String mtlsCertFingerprint) {

    public enum PartnerType {
        LOCAL,
        OVERSEAS
    }
}
