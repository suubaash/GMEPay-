package com.gme.pay.auth.service;

import com.gme.pay.auth.domain.HmacSignatureVerifier;
import com.gme.pay.auth.domain.NonceStore;
import com.gme.pay.auth.domain.PartnerCredentialPort;
import com.gme.pay.auth.domain.PartnerCredentialPort.ResolvedCredential;
import com.gme.pay.auth.domain.TimestampValidator;
import com.gme.pay.auth.dto.VerifyRequest;
import com.gme.pay.auth.dto.VerifyResponse;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates HMAC-SHA256 partner request-signature verification.
 *
 * Steps (SEC-09 §3.3, API-05 §3.2):
 *  1. Resolve partner credential by API key → obtain hmac_secret and partner_id.
 *  2. Validate X-Timestamp within 300-second window.
 *  3. Check (partnerId, X-Nonce) for replay within 600-second window.
 *  4. Build canonical string and verify HMAC-SHA256 signature (constant-time compare).
 */
@Service
public class AuthVerificationService {

    /** Reject requests whose timestamp is further than this from server time. */
    private static final long TIMESTAMP_WINDOW_SECONDS = 300L;

    /** Retain nonces for replay detection for this duration. */
    private static final Duration NONCE_TTL = Duration.ofSeconds(600);

    private final PartnerCredentialPort credentialPort;
    private final NonceStore nonceStore;

    public AuthVerificationService(PartnerCredentialPort credentialPort,
                                   NonceStore nonceStore) {
        this.credentialPort = credentialPort;
        this.nonceStore = nonceStore;
    }

    /**
     * Verifies the partner request signature.
     *
     * @param request verification parameters (api key, headers, body hash)
     * @return {@link VerifyResponse} indicating success or failure with an error code
     */
    public VerifyResponse verify(VerifyRequest request) {
        String requestId = UUID.randomUUID().toString();

        // 1. Resolve partner credential
        Optional<ResolvedCredential> credOpt =
                credentialPort.findActiveByApiKey(request.apiKey());
        if (credOpt.isEmpty()) {
            return VerifyResponse.fail("INVALID_API_KEY", requestId);
        }
        ResolvedCredential cred = credOpt.get();

        // 2. Timestamp window validation
        try {
            if (!TimestampValidator.isWithinWindow(request.timestamp(), Instant.now(),
                    TIMESTAMP_WINDOW_SECONDS)) {
                return VerifyResponse.fail("TIMESTAMP_DRIFT", requestId);
            }
        } catch (IllegalArgumentException e) {
            return VerifyResponse.fail("TIMESTAMP_DRIFT", requestId);
        }

        // 3. Nonce replay detection
        boolean nonceOk = nonceStore.checkAndSet(
                String.valueOf(cred.partnerId()), request.nonce(), NONCE_TTL);
        if (!nonceOk) {
            return VerifyResponse.fail("REPLAY_DETECTED", requestId);
        }

        // 4. HMAC-SHA256 signature verification (constant-time)
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                request.httpMethod(),
                request.pathWithQuery(),
                request.timestamp(),
                request.bodyHash());

        boolean signatureValid = HmacSignatureVerifier.verifySignature(
                request.signature(), canonical, cred.hmacSecret());

        if (!signatureValid) {
            return VerifyResponse.fail("INVALID_SIGNATURE", requestId);
        }

        return VerifyResponse.ok(cred.partnerId(), requestId);
    }
}
