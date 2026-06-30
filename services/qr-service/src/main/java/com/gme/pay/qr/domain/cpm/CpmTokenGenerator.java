package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.cpm.PrepareTokenIssuancePort.CpmPrepareContext;
import com.gme.pay.qr.domain.cpm.PrepareTokenIssuancePort.PrepareTokenResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds a {@link CpmToken} for ZeroPay (WBS 5.3) by delegating prepare-token issuance to the
 * {@link PrepareTokenIssuancePort}.
 *
 * <p>The opaque {@code prepare_token} and QR content come from the port — in production the
 * ZeroPay scheme adapter (INTEGRATION REQUEST #1), in this wave the local-issuance fallback. This
 * class only assigns the platform identifiers (cpm_token_id, payment_id) and assembles the record.
 */
@Component
public class CpmTokenGenerator {

    private final PrepareTokenIssuancePort issuancePort;
    private final int tokenTtlSeconds;

    public CpmTokenGenerator(PrepareTokenIssuancePort issuancePort,
                             @Value("${qr.zeropay.cpm-token-ttl-seconds:60}") int tokenTtlSeconds) {
        this.issuancePort = issuancePort;
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    /**
     * Generate a new CPM token for the given request context.
     *
     * @param schemeId      resolved scheme identifier (e.g. "ZEROPAY")
     * @param partnerTxnRef partner's own transaction reference
     * @param customerRef   hashed customer identifier supplied by the partner
     * @param countryCode   ISO 3166-1 alpha-2 country code
     * @return the generated token plus whether it was scheme-issued
     */
    public GeneratedToken generate(String schemeId, String partnerTxnRef,
                                   String customerRef, String countryCode) {
        String tokenId   = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String paymentId = "PMT-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();

        PrepareTokenResult issued = issuancePort.issue(new CpmPrepareContext(
                schemeId, partnerTxnRef, customerRef, countryCode, tokenTtlSeconds));

        CpmToken token = new CpmToken(tokenId, paymentId, issued.prepareToken(),
                issued.qrContent(), schemeId, partnerTxnRef, issued.issuedAt(), issued.expiresAt());
        return new GeneratedToken(token, issued.schemeIssued());
    }

    public int getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    /**
     * A generated CPM token plus provenance.
     *
     * @param token        the assembled token
     * @param schemeIssued {@code true} if from the real scheme; {@code false} for local fallback
     */
    public record GeneratedToken(CpmToken token, boolean schemeIssued) {}
}
