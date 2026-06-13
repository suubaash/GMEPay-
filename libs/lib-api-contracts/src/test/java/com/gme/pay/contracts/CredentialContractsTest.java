package com.gme.pay.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 8 Lane B contract pins for the credential DTO surface:
 * {@link IssuedCredentialBundle} (the one-time plaintext carrier),
 * {@link PartnerCredentialView} / {@link PartnerIpAllowlistView} /
 * {@link PartnerMtlsCertView} (display residue, never plaintext) and the
 * three step-8 {@link PartnerCommand} sub-commands.
 */
class CredentialContractsTest {

    private static IssuedCredentialBundle bundle() {
        return new IssuedCredentialBundle(
                "pk_test_abc123wxyz",
                "sk_test_SUPERSECRET",
                "whsec_test_ALSOSECRET",
                "pk_test_abc123wxyz",
                "pk_test_",
                "wxyz",
                Instant.parse("2027-06-13T00:00:00Z"));
    }

    @Test
    @DisplayName("SEC-09 §4: IssuedCredentialBundle.toString redacts every secret component")
    void bundleToString_redactsSecrets() {
        String rendered = bundle().toString();
        assertFalse(rendered.contains("SUPERSECRET"),
                "hmac secret leaked through toString(): " + rendered);
        assertFalse(rendered.contains("ALSOSECRET"),
                "webhook secret leaked through toString(): " + rendered);
        assertTrue(rendered.contains("REDACTED"));
        // The display residue stays visible for diagnostics.
        assertTrue(rendered.contains("pk_test_"));
        assertTrue(rendered.contains("wxyz"));
    }

    @Test
    @DisplayName("Bundle components round-trip; accessors expose the one-time plaintext once")
    void bundleComponents_roundTrip() {
        IssuedCredentialBundle b = bundle();
        assertEquals("pk_test_abc123wxyz", b.apiKeyPlaintext());
        assertEquals("sk_test_SUPERSECRET", b.hmacSecretPlaintext());
        assertEquals("whsec_test_ALSOSECRET", b.webhookSecretPlaintext());
        assertEquals(b.apiKeyPlaintext(), b.apiKeyId());
        assertEquals("pk_test_", b.prefix());
        assertEquals("wxyz", b.last4());
        assertEquals(Instant.parse("2027-06-13T00:00:00Z"), b.expiresAt());
    }

    @Test
    @DisplayName("PartnerCredentialView carries display residue only — no plaintext component exists")
    void credentialView_hasNoPlaintextComponents() {
        // Compile-time shape pin: the view's full component list. If somebody
        // adds a plaintext-bearing component this stops compiling / fails.
        PartnerCredentialView view = new PartnerCredentialView(
                1L, "SANDBOX", "API_KEY", "pk_test_abc123wxyz", "pk_test_", "wxyz",
                Instant.now(), Instant.now(), null, null, "ACTIVE");
        assertEquals("pk_test_abc123wxyz", view.authIdentityKeyId());
        assertNull(view.rotatedAt());
        for (var component : PartnerCredentialView.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().contains("plaintext"),
                    "PartnerCredentialView must never carry plaintext: "
                            + component.getName());
            assertFalse(component.getName().toLowerCase().contains("secret"),
                    "PartnerCredentialView must never carry secret material: "
                            + component.getName());
        }
    }

    @Test
    @DisplayName("Step-8 PartnerCommand sub-commands nest without churning the wrapper")
    void step8Commands_nest() {
        PartnerCommand.UpdateStep8Credentials allowlist =
                new PartnerCommand.UpdateStep8Credentials(List.of(
                        new PartnerIpAllowlistCommand("203.0.113.0/24", "office", "SANDBOX")));
        assertEquals(1, allowlist.ipAllowlist().size());
        assertEquals("203.0.113.0/24", allowlist.ipAllowlist().get(0).cidr());

        PartnerCommand.UploadMtlsCert upload =
                new PartnerCommand.UploadMtlsCert("PRODUCTION", "-----BEGIN CERTIFICATE-----");
        assertEquals("PRODUCTION", upload.environment());
        assertNotNull(upload.certPem());

        PartnerCommand.RotateCredentials rotate =
                new PartnerCommand.RotateCredentials("SANDBOX");
        assertEquals("SANDBOX", rotate.environment());
    }

    @Test
    @DisplayName("PartnerActivationView pairs the partner view with the one-time bundle")
    void activationView_pairsPartnerAndBundle() {
        PartnerActivationView view = new PartnerActivationView(null, bundle());
        assertNotNull(view.credentials());
        assertEquals("pk_test_", view.credentials().prefix());
        // The wrapper's own toString must not leak either (records delegate
        // to the component's redacting toString).
        assertFalse(view.toString().contains("SUPERSECRET"));
    }
}
