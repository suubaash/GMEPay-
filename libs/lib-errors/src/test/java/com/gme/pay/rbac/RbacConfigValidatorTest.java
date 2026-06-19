package com.gme.pay.rbac;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.rbac.RbacAutoConfiguration.RbacConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fail-closed startup guard (#90 HIGH): ENFORCE without a verify secret must NOT silently fall back to trust. */
class RbacConfigValidatorTest {

    private static RbacConfigValidator validator(RbacMode mode, String secret, boolean allowUnsigned) {
        RbacProperties p = new RbacProperties();
        p.setEnabled(true);
        p.setMode(mode);
        p.getVerify().setSecret(secret);
        p.getVerify().setAllowUnsigned(allowUnsigned);
        return new RbacConfigValidator(p);
    }

    @Test
    @DisplayName("ENFORCE + blank secret + not allow-unsigned → fails startup (fail-closed)")
    void enforceWithoutSecretFailsClosed() {
        assertThrows(IllegalStateException.class, () -> validator(RbacMode.ENFORCE, "", false).afterPropertiesSet());
    }

    @Test
    @DisplayName("ENFORCE + secret set → starts")
    void enforceWithSecretOk() {
        assertDoesNotThrow(() -> validator(RbacMode.ENFORCE, "edge-secret", false).afterPropertiesSet());
    }

    @Test
    @DisplayName("AUDIT mode does not require a secret")
    void auditModeOkWithoutSecret() {
        assertDoesNotThrow(() -> validator(RbacMode.AUDIT, "", false).afterPropertiesSet());
    }

    @Test
    @DisplayName("explicit allow-unsigned escape hatch lets ENFORCE start without a secret")
    void allowUnsignedEscapeHatch() {
        assertDoesNotThrow(() -> validator(RbacMode.ENFORCE, "", true).afterPropertiesSet());
    }
}
