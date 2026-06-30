package com.gme.pay.qr.domain.cpm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Self-contained local-issuance fallback for {@link PrepareTokenIssuancePort} (WBS 5.3-T06).
 *
 * <p>Produces a deterministic-format opaque token ({@code ZP-CPM-<20-hex>}) so the CPM generate
 * endpoint functions end-to-end without the ZeroPay scheme adapter. Tokens issued here are
 * <strong>not</strong> valid at a real POS — {@link PrepareTokenResult#schemeIssued()} is
 * {@code false}. When the genuine scheme-adapter-backed bean is supplied (INTEGRATION REQUEST #1),
 * it takes precedence via {@link ConditionalOnMissingBean}.
 */
@Component
@ConditionalOnMissingBean(name = "schemePrepareTokenIssuancePort")
public class LocalPrepareTokenIssuer implements PrepareTokenIssuancePort {

    @Override
    public PrepareTokenResult issue(CpmPrepareContext context) {
        String hex = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String prepareToken = "ZP-CPM-" + hex.substring(0, 20);
        // Minimal EMVCo-style TLV envelope carrying the token in the MAI slot; round-trips
        // through CpmPayloadParser.parseCpmToken so generate/parse are symmetric for tests.
        String qrContent = CpmPayloadEncoder.encode(prepareToken, context.schemeId());

        Instant now = Instant.now();
        return new PrepareTokenResult(
                prepareToken, qrContent, now, now.plusSeconds(context.tokenTtlSeconds()), false);
    }
}
