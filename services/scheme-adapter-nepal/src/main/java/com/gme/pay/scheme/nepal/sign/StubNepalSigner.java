package com.gme.pay.scheme.nepal.sign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;

/**
 * Default {@link NepalRequestSigner} for local/sim use.
 *
 * <p>It injects {@code nonce} = current epoch seconds into the payload, base64-encodes the
 * JSON into {@code data}, and returns a fixed placeholder {@code signature}. The sim
 * ({@code sim-nepal-qr}) base64-decodes {@code data} and only soft-logs the signature, so
 * this is sufficient end-to-end against the sim.
 *
 * <p><b>TODO — real signer:</b> a production {@code RsaNepalSigner} must replace this bean.
 * Per {@code issuance-extension.txt} the real signature is
 * {@code base64( RSA-2048 / PKCS#1 v1.5 / SHA-256 sign( base64(json) ) )} using the
 * Khalti-provided private key. Steps: SHA-256 hash the base64({@code data}) bytes, sign with
 * PKCS#1 v1.5, base64 the signature. NOT implemented here (no key material, no real endpoint).
 * The nonce must be Nepal-time (UTC+5:45) UNIX seconds within the server's
 * {@code serverTs-100 .. serverTs+200} window.
 */
@Component
public class StubNepalSigner implements NepalRequestSigner {

    /** Placeholder base64 signature; overwritten by the real RSA signer in production. */
    static final String PLACEHOLDER_SIGNATURE = "c3R1Yi1zaWduYXR1cmU=";

    private final ObjectMapper mapper;

    public StubNepalSigner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SignedEnvelope sign(String jsonPayload) {
        long nonce = Instant.now().getEpochSecond();
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(jsonPayload);
            node.put("nonce", nonce);
            byte[] jsonBytes = mapper.writeValueAsBytes(node);
            String dataB64 = Base64.getEncoder().encodeToString(jsonBytes);
            // Real signer signs SHA-256(dataB64) with RSA-2048/PKCS#1 here.
            return new SignedEnvelope(dataB64, PLACEHOLDER_SIGNATURE, nonce);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "failed to build signed Nepal envelope: " + e.getMessage());
        }
    }
}
