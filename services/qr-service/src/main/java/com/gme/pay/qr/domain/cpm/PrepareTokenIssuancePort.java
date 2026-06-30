package com.gme.pay.qr.domain.cpm;

import java.time.Instant;

/**
 * Anti-corruption port for obtaining a scheme-issued CPM {@code prepare_token} (WBS 5.3-T06).
 *
 * <p>In production the genuine implementation calls the ZeroPay scheme adapter
 * ({@code POST /internal/v1/scheme/zeropay/cpm/prepare}) which relays 한결원's real-time CPM
 * prepare API and returns a one-time opaque token the partner renders as a QR code. That adapter
 * also performs the prefunding reservation. Both belong to OTHER services and are FROZEN for this
 * module, so the contract is captured here as a port and recorded as an INTEGRATION REQUEST.
 *
 * <p>Until the scheme adapter lands, {@link LocalPrepareTokenIssuer} provides a self-contained
 * local-issuance fallback so the qr-service half (request/response contract + persistence) works
 * end-to-end. The fallback is clearly marked non-authoritative via {@link PrepareTokenResult#schemeIssued()}.
 */
public interface PrepareTokenIssuancePort {

    /**
     * Issue a CPM prepare token for the given context.
     *
     * @param context the resolved CPM prepare context; never null
     * @return the issued token result; never null
     * @throws com.gme.pay.qr.exception.SchemeUnavailableException if a real scheme is wired but
     *                                                             unreachable (retryable)
     */
    PrepareTokenResult issue(CpmPrepareContext context);

    /**
     * Input context for a CPM prepare-token request — everything the scheme needs, scheme-agnostic.
     *
     * @param schemeId      resolved scheme identifier (e.g. "ZEROPAY")
     * @param partnerTxnRef partner's own transaction reference
     * @param customerRef   hashed customer identifier supplied by the partner
     * @param countryCode   ISO 3166-1 alpha-2 country code
     * @param tokenTtlSeconds requested token time-to-live in seconds
     */
    record CpmPrepareContext(String schemeId, String partnerTxnRef,
                             String customerRef, String countryCode, int tokenTtlSeconds) {}

    /**
     * Result of a prepare-token issuance.
     *
     * @param prepareToken the opaque one-time token (rendered into the QR)
     * @param qrContent    the QR content the partner renders as an image
     * @param issuedAt     issuance timestamp
     * @param expiresAt    hard expiry (issuedAt + ttl)
     * @param schemeIssued {@code true} when the token came from the real scheme; {@code false}
     *                     when it is a locally-issued fallback (NOT valid at a real POS)
     */
    record PrepareTokenResult(String prepareToken, String qrContent,
                              Instant issuedAt, Instant expiresAt, boolean schemeIssued) {}
}
