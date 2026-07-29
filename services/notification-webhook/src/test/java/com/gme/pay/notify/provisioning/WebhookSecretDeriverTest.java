package com.gme.pay.notify.provisioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebhookSecretDeriver} — the T5-4 replacement for the one global webhook signing
 * secret. Pins the four properties the fix depends on: the derivation is deterministic
 * (so the dispatcher can re-derive what registration revealed), it is <b>independent per
 * partner, per environment and per generation</b> (so one partner's secret forges nothing
 * for anyone else and rotation really re-keys), the output keeps the {@code whsec_} shape
 * the registration contract requires, and it fails closed with no root key.
 */
class WebhookSecretDeriverTest {

    private static final String ROOT = "root-key-under-test-0123456789abcdef";

    private final WebhookSecretDeriver deriver = WebhookSecretDeriver.withRootKey(ROOT);

    @Test
    @DisplayName("deterministic: the same endpoint identity always derives the same secret")
    void derivationIsDeterministic() {
        assertEquals(deriver.derive(1L, "LIVE", 1), deriver.derive(1L, "LIVE", 1));
        // A second deriver over the same root key must agree — this is what lets the
        // dispatcher reproduce a secret minted by an earlier process.
        assertEquals(deriver.derive(1L, "LIVE", 1),
                WebhookSecretDeriver.withRootKey(ROOT).derive(1L, "LIVE", 1));
    }

    @Test
    @DisplayName("shape: whsec_ + 43 base64url chars, same as a CSPRNG-minted secret")
    void secretKeepsTheRegistrationContractShape() {
        String secret = deriver.derive(1L, "LIVE", 1);
        assertTrue(secret.startsWith(SigningSecrets.SECRET_PREFIX));
        assertEquals(SigningSecrets.SECRET_PREFIX.length() + 43, secret.length());
        assertTrue(secret.substring(SigningSecrets.SECRET_PREFIX.length()).matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    @DisplayName("isolation: partner, environment and generation each change the secret")
    void everyIdentityComponentChangesTheSecret() {
        String base = deriver.derive(1L, "LIVE", 1);
        assertNotEquals(base, deriver.derive(2L, "LIVE", 1), "different partner");
        assertNotEquals(base, deriver.derive(1L, "SANDBOX", 1), "different environment");
        assertNotEquals(base, deriver.derive(1L, "LIVE", 2), "different generation");
    }

    @Test
    @DisplayName("a different root key yields entirely different secrets")
    void rootKeyIsPartOfTheDerivation() {
        assertNotEquals(deriver.derive(1L, "LIVE", 1),
                WebhookSecretDeriver.withRootKey("some-other-root-key").derive(1L, "LIVE", 1));
    }

    @Test
    @DisplayName("fail closed: no root key ⇒ not configured, and deriving throws rather than guessing")
    void failsClosedWithoutARootKey() {
        WebhookSecretDeriver unconfigured = WebhookSecretDeriver.withRootKey("");
        assertFalse(unconfigured.isConfigured());
        assertThrows(IllegalStateException.class, () -> unconfigured.derive(1L, "LIVE", 1));

        WebhookSecretDeriver nullKey = WebhookSecretDeriver.withRootKey(null);
        assertFalse(nullKey.isConfigured());
        assertThrows(IllegalStateException.class, () -> nullKey.derive(1L, "LIVE", 1));
    }

    @Test
    @DisplayName("rejects a missing partner id or a generation below 1")
    void rejectsMalformedIdentity() {
        assertTrue(deriver.isConfigured());
        assertThrows(IllegalArgumentException.class, () -> deriver.derive(null, "LIVE", 1));
        assertThrows(IllegalArgumentException.class, () -> deriver.derive(1L, "LIVE", 0));
    }
}
