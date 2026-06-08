package com.gme.pay.qr.domain.cpm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Generates CPM (Consumer Presented Mode) token identifiers for ZeroPay (WBS 5.3).
 *
 * <p>In the real implementation this class would call the ZeroPay scheme adapter to obtain
 * a one-time {@code prepare_token}. Here the logic is self-contained so the REST endpoint
 * can function end-to-end without an external dependency: a deterministic token is produced
 * from a UUID and the configured TTL is honoured.
 *
 * <p>The ZeroPay adapter is consumed via {@link MerchantQrDataPort} (interface inside this
 * module) — no other service module is imported.
 */
@Component
public class CpmTokenGenerator {

    private final int tokenTtlSeconds;

    public CpmTokenGenerator(@Value("${qr.zeropay.cpm-token-ttl-seconds:60}") int tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * Generate a new CPM token for the given request context.
     *
     * @param schemeId       resolved scheme identifier (e.g. "ZEROPAY")
     * @param partnerTxnRef  partner's own transaction reference
     * @param customerRef    hashed customer identifier supplied by the partner
     * @param countryCode    ISO 3166-1 alpha-2 country code
     * @return a fully populated {@link CpmToken}
     */
    public CpmToken generate(String schemeId, String partnerTxnRef,
                             String customerRef, String countryCode) {
        String tokenId   = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String paymentId = "PMT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

        // Opaque prepare token — in production this comes from the ZeroPay adapter.
        // Format mirrors the sandbox stub: ZP-CPM-<20-hex-chars>
        String prepareToken = "ZP-CPM-" + tokenId.substring(0, 20);

        // QR content is the prepare_token encoded in a minimal EMVCo TLV envelope.
        // The actual content would be scheme-spec formatted; here we produce a stable stub value.
        String qrContent = "QR:" + prepareToken;

        Instant now       = Instant.now();
        Instant expiresAt = now.plusSeconds(tokenTtlSeconds);

        return new CpmToken(tokenId, paymentId, prepareToken, qrContent,
                            schemeId, partnerTxnRef, now, expiresAt);
    }

    public int getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
