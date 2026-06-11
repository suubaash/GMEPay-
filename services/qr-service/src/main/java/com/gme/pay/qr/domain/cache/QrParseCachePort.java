package com.gme.pay.qr.domain.cache;

import com.gme.pay.qr.domain.emvco.ParsedQRPayload;

import java.util.Optional;

/**
 * Port for the QR-parse result cache (17.2-G04).
 *
 * <p>The EMVCo parse of a given raw payload is deterministic, so successful parse results
 * are cached keyed by the raw payload (implementations hash it). The domain/REST layer
 * talks only to this interface; the JPA adapter lives in {@code com.gme.pay.qr.persistence}.
 * Failed parses are never cached — every error path re-runs full validation.
 */
public interface QrParseCachePort {

    /**
     * Look up a previously cached parse result for the given raw QR payload.
     *
     * @param rawPayload the complete raw QR string exactly as received
     * @return the cached result, or empty if this payload has not been parsed before
     */
    Optional<ParsedQRPayload> findCached(String rawPayload);

    /**
     * Store a successful parse result. Re-storing the same raw payload is an upsert
     * (single row per payload).
     *
     * @param parsed a fully populated parse result; never null
     */
    void store(ParsedQRPayload parsed);
}
