package com.gme.pay.bff.web;

import com.gme.pay.bff.security.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-CLOSED semantics of {@link OpsRbacGuard}, now sourced from the VERIFIED TOKEN rather than
 * the client-supplied {@code X-Gme-Permissions} header (gap register T0-3).
 *
 * <p>The "no permissions presented" case is no longer "header absent" — it is "the token carries no
 * permissions claim". A caller with no token at all never reaches the guard: the
 * {@link com.gme.pay.bff.config.BffSecurityConfig} filter chain 401s first (covered by
 * {@code BffSecurityFilterChainTest}).
 */
class OpsRbacGuardTest {

    @AfterEach
    void tearDown() {
        TestTokens.clear();
    }

    @Test
    @DisplayName("enforce: an unauthenticated caller is denied (no identity ⇒ no permissions)")
    void enforce_deniesWhenUnauthenticated() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        assertThatThrownBy(guard::requireOps)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("enforce: a token with an empty permissions claim is denied")
    void enforce_deniesWhenTokenHasNoPermissions() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.hubOperator();
        assertThatThrownBy(guard::requireOps)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("enforce: a token holding other permissions is denied the ops action")
    void enforce_deniesWhenTokenLacksOps() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.hubOperator("partner.view", "txn.view");
        assertThatThrownBy(guard::requireOps)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("enforce: a token carrying ops:operate is allowed")
    void enforce_allowsWhenOpsPresent() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.hubOperator("partner.view", "ops:operate");
        assertThatCode(guard::requireOps).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a forged X-Gme-Permissions header can no longer authorize anything")
    void headerIsNoLongerAnAuthorizationSource() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        // The header is not even an input any more — there is no requireOps(String) overload.
        // Proof of the fix: an identity whose TOKEN lacks ops:operate is denied, regardless of
        // whatever a caller might put on the wire.
        TestTokens.hubOperator("txn.view");
        assertThatThrownBy(guard::requireOps).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("txn.view grants the support read surface; ops:operate implies it")
    void txnViewAndOpsBothSatisfyTheReadGate() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.hubOperator("txn.view");
        assertThatCode(guard::requireTxnView).doesNotThrowAnyException();
        TestTokens.hubOperator("ops:operate");
        assertThatCode(guard::requireTxnView).doesNotThrowAnyException();
        TestTokens.hubOperator("partner.view");
        assertThatThrownBy(guard::requireTxnView).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("dev gate-off allows a permission-less token but still denies a wrong permission set")
    void devGateOff_allowsPermissionlessToken_butStillDeniesWrongSet() {
        OpsRbacGuard guard = new OpsRbacGuard(false);
        TestTokens.hubOperator();
        assertThatCode(guard::requireOps).doesNotThrowAnyException();
        TestTokens.hubOperator("partner.view");
        assertThatThrownBy(guard::requireOps).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("admin surface: reads need any hub permission, writes need a write permission")
    void adminReadAndWriteGates() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        // read-only HUB_OPERATOR grant set
        TestTokens.hubOperator("partner.view", "txn.view", "report.generate");
        assertThatCode(guard::requireAdminRead).doesNotThrowAnyException();
        assertThatThrownBy(guard::requireAdminWrite)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
        // a partner-scoped token holds no hub permission at all -> cannot even read admin
        TestTokens.partner("PARTNER-A");
        assertThatThrownBy(guard::requireAdminRead).isInstanceOf(ResponseStatusException.class);
        // an onboarding operator can write
        TestTokens.hubOperator("partner.activate");
        assertThatCode(guard::requireAdminWrite).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("actor(): the token subject wins over a caller-supplied principal label")
    void actorPrefersTokenSubject() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.authenticate("real-operator", null, "ops:operate");
        assertThat(guard.actor("i-am-somebody-else")).isEqualTo("real-operator");
        TestTokens.clear();
        assertThat(guard.actor("legacy-header-value")).isEqualTo("legacy-header-value");
        assertThat(guard.actor(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("permissions from PERM_* authorities are honoured for non-JWT authentications")
    void permissionsFromAuthorities() {
        OpsRbacGuard guard = new OpsRbacGuard(true);
        TestTokens.authenticateWithAuthorities("svc-1", "ops:operate");
        assertThatCode(guard::requireOps).doesNotThrowAnyException();
    }
}
