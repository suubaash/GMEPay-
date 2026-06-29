package com.gme.pay.internalauth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.internalauth.InternalAuthAutoConfiguration.InternalAuthConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fail-closed startup guard (#90): enabling the internal-auth gate without a secret would admit everyone. */
class InternalAuthConfigValidatorTest {

    private static InternalAuthConfigValidator validator(String secret) {
        InternalAuthProperties p = new InternalAuthProperties();
        p.setEnabled(true);
        p.setSecret(secret);
        return new InternalAuthConfigValidator(p);
    }

    @Test
    @DisplayName("enabled + blank secret → fails startup (fail-closed)")
    void enabledWithoutSecretFailsClosed() {
        assertThrows(IllegalStateException.class, () -> validator("").afterPropertiesSet());
        assertThrows(IllegalStateException.class, () -> validator("   ").afterPropertiesSet());
    }

    @Test
    @DisplayName("enabled + secret set → starts")
    void enabledWithSecretOk() {
        assertDoesNotThrow(() -> validator("internal-svc-secret").afterPropertiesSet());
    }
}
