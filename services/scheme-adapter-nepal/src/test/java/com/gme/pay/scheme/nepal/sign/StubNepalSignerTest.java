package com.gme.pay.scheme.nepal.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubNepalSignerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StubNepalSigner signer = new StubNepalSigner(mapper);

    @Test
    @DisplayName("sign: injects nonce, base64-encodes data, nonce matches embedded value")
    void sign_producesEnvelope() throws Exception {
        long before = java.time.Instant.now().getEpochSecond();
        NepalRequestSigner.SignedEnvelope env = signer.sign("{\"reference\":\"REF-1\",\"amount\":\"1000\"}");

        assertNotNull(env.data());
        assertNotNull(env.signature());
        assertTrue(env.nonce() >= before);

        byte[] decoded = Base64.getDecoder().decode(env.data());
        JsonNode json = mapper.readTree(new String(decoded, StandardCharsets.UTF_8));
        assertEquals("REF-1", json.path("reference").asText());
        assertEquals(env.nonce(), json.path("nonce").asLong());
    }
}
