package com.gme.pay.scheme.sendmn.crypto;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Selects the active {@link SendmnEnvelopeCodec} via {@code sendmn.envelope.mode}:
 * {@code plain} (default — local dev / sim) or {@code rsa} (best-effort hybrid; keys
 * supplied as base64 DER via {@code sendmn.envelope.rsa.*}, empty placeholders in yml).
 */
@Configuration
public class EnvelopeCodecConfig {

    @Bean
    SendmnEnvelopeCodec sendmnEnvelopeCodec(
            @Value("${sendmn.envelope.mode:plain}") String mode,
            @Value("${sendmn.envelope.rsa.sendmn-public-key:}") String sendmnPublicKeyB64,
            @Value("${sendmn.envelope.rsa.partner-private-key:}") String partnerPrivateKeyB64) {
        if ("rsa".equalsIgnoreCase(mode)) {
            return new RsaAesEnvelopeCodec(
                    loadPublicKey(sendmnPublicKeyB64),
                    loadPrivateKey(partnerPrivateKeyB64));
        }
        if (!"plain".equalsIgnoreCase(mode)) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn.envelope.mode must be 'plain' or 'rsa', got: " + mode);
        }
        return new PlainJsonEnvelopeCodec();
    }

    /** base64 DER (X.509 SubjectPublicKeyInfo) → RSA public key; blank → null (fail on use). */
    static PublicKey loadPublicKey(String base64Der) {
        if (base64Der == null || base64Der.isBlank()) return null;
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Der.trim())));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn.envelope.rsa.sendmn-public-key is not valid base64 DER: " + e.getMessage());
        }
    }

    /** base64 DER (PKCS#8) → RSA private key; blank → null (fail on use). */
    static PrivateKey loadPrivateKey(String base64Der) {
        if (base64Der == null || base64Der.isBlank()) return null;
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Der.trim())));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "sendmn.envelope.rsa.partner-private-key is not valid base64 DER: " + e.getMessage());
        }
    }
}
