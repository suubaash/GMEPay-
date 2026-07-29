package com.gme.sim.sendmn.envelope;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Plain-JSON body envelope — mirror of scheme-adapter-sendmn's
 * {@code PlainJsonEnvelopeCodec}: the {@code encryptedData} value is simply
 * base64(UTF-8 JSON), keeping the envelope <em>shape</em> identical to production
 * (real RSA hybrid pending SendMN wire-spec confirmation, open issue O2).
 */
public final class PlainEnvelope {

    private PlainEnvelope() {
    }

    public static String encode(String plainJson) {
        return Base64.getEncoder().encodeToString(plainJson.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the value is not valid base64 */
    public static String decode(String encryptedData) {
        return new String(Base64.getDecoder().decode(encryptedData), StandardCharsets.UTF_8);
    }
}
