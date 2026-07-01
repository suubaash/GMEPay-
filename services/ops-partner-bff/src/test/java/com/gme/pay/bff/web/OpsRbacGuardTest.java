package com.gme.pay.bff.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-CLOSED semantics of {@link OpsRbacGuard} (defect #2a).
 */
class OpsRbacGuardTest {

    @Test
    void enforce_deniesWhenHeaderAbsent() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        assertThatThrownBy(() -> guard.requireOps(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> guard.requireOps("   "))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enforce_deniesWhenHeaderLacksOps() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        assertThatThrownBy(() -> guard.requireOps("partner:read,partner:write"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void enforce_allowsWhenOpsPresent() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        assertThatCode(() -> guard.requireOps("partner:read, ops:operate")).doesNotThrowAnyException();
    }

    @Test
    void devGateOff_allowsAbsentHeader_butStillDeniesWrongHeader() {
        OpsRbacGuard guard = new OpsRbacGuard(false);
        // absent header -> allowed (dev)
        assertThatCode(() -> guard.requireOps(null)).doesNotThrowAnyException();
        // present-but-wrong -> still denied even with the gate off
        assertThatThrownBy(() -> guard.requireOps("partner:read"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
