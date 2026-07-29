package com.gme.pay.scheme.sendmn.crypto;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Dev/sim {@link SendmnEnvelopeCodec}: the {@code encryptedData} value is simply
 * base64(UTF-8 JSON) — no cryptography. Keeps the envelope <em>shape</em> identical to
 * production so the client, sim and tests exercise the same wrap/unwrap path, while the
 * real RSA hybrid ({@link RsaAesEnvelopeCodec}) stays config-switched off until SendMN
 * confirms the exact wire spec (open issue O2).
 */
public class PlainJsonEnvelopeCodec implements SendmnEnvelopeCodec {

    @Override
    public String mode() {
        return "plain";
    }

    @Override
    public String encrypt(String plainJson) {
        if (plainJson == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "sendmn envelope: null payload");
        }
        return Base64.getEncoder().encodeToString(plainJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "sendmn envelope: empty encryptedData");
        }
        try {
            return new String(Base64.getDecoder().decode(encryptedData), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "sendmn envelope: encryptedData is not valid base64: " + e.getMessage());
        }
    }
}
