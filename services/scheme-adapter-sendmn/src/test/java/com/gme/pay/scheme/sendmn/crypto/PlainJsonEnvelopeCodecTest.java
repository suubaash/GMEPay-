package com.gme.pay.scheme.sendmn.crypto;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Round-trip tests for the dev/sim {@link PlainJsonEnvelopeCodec}. */
class PlainJsonEnvelopeCodecTest {

    private final PlainJsonEnvelopeCodec codec = new PlainJsonEnvelopeCodec();

    @Test
    @DisplayName("round-trip: encrypt then decrypt returns the original JSON")
    void roundTrip() {
        String json = "{\"QR_CODE\":\"00020101021229...MN\",\"TX_TOKEN_NO\":\"SMN202510241041\"}";

        String enveloped = codec.encrypt(json);

        assertNotEquals(json, enveloped, "envelope value must not be the raw JSON");
        assertEquals(json, codec.decrypt(enveloped));
    }

    @Test
    @DisplayName("encrypt produces valid base64 of the UTF-8 JSON (envelope shape holds)")
    void encryptIsBase64() {
        String json = "{\"a\":\"монгол\"}"; // non-ASCII survives the UTF-8 round-trip

        String enveloped = codec.encrypt(json);

        assertEquals(json, new String(Base64.getDecoder().decode(enveloped),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("decrypt: blank/garbage encryptedData → VALIDATION_ERROR")
    void decryptRejectsGarbage() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> codec.decrypt("")).errorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR,
                assertThrows(ApiException.class, () -> codec.decrypt("%%not-base64%%")).errorCode());
    }

    @Test
    @DisplayName("mode is 'plain'")
    void mode() {
        assertEquals("plain", codec.mode());
    }
}
