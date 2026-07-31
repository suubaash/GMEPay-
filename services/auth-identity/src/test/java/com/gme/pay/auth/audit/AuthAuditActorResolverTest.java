package com.gme.pay.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.rbac.RbacHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * T5-1's core property, isolated: <b>a claim on the wire cannot become an attested identity.</b>
 *
 * <p>Sealing an audit row proves it has not been edited since it was written. It says nothing
 * about whether what was written was true — so a hash chain over a forged actor is a
 * tamper-evident lie. These tests pin the decision that keeps the actor column honest, which is
 * the half of the gap that a hash chain cannot fix.
 */
class AuthAuditActorResolverTest {

    private static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private static AuthAuditActorResolver resolver() {
        return new AuthAuditActorResolver(SECRET, false, false);
    }

    @Test
    @DisplayName("attested caller + forwarded principal → the principal, unprefixed")
    void provenCallerWithPrincipal_isAttested() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");

        String actor = resolver().resolve(req);

        assertThat(actor).isEqualTo("alice@gme.com");
        assertThat(AuditActors.isAttributable(actor)).isTrue();
    }

    @Test
    @DisplayName("X-Actor is honoured as the secondary claim header")
    void provenCallerWithLegacyActorHeader_isAttested() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        req.addHeader(AuthAuditActorResolver.ACTOR_HEADER, "bob@gme.com");

        assertThat(resolver().resolve(req)).isEqualTo("bob@gme.com");
    }

    @Test
    @DisplayName("attested caller, no principal → svc:internal-caller (the service, not a human)")
    void provenCallerWithoutPrincipal_isAService() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        String actor = resolver().resolve(req);

        assertThat(actor).isEqualTo("svc:" + AuthAuditActorResolver.CALLER_SERVICE);
        // The service is genuinely attested — the row states which of the two we know.
        assertThat(AuditActors.isAttributable(actor)).isTrue();
    }

    @Test
    @DisplayName("FORGED claim with no credential → unverified:, never an attested principal")
    void forgedClaimWithoutCredential_isNeverAttested() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");   // no internal token

        String actor = resolver().resolve(req);

        assertThat(actor).isEqualTo("unverified:alice@gme.com");
        // Structurally impossible to mistake for, or join against, the real alice@gme.com.
        assertThat(actor).isNotEqualTo("alice@gme.com");
        assertThat(AuditActors.isAttributable(actor)).isFalse();
    }

    @Test
    @DisplayName("a WRONG credential is not a credential")
    void wrongCredential_doesNotAttest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET + "-tampered");
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");

        assertThat(resolver().resolve(req)).isEqualTo("unverified:alice@gme.com");
    }

    @Test
    @DisplayName("nothing claimed and nothing proven → 'unattributed', never 'system'")
    void nothingClaimedNothingProven_isUnattributed() {
        String actor = resolver().resolve(new MockHttpServletRequest());

        assertThat(actor).isEqualTo(AuditActors.UNATTRIBUTED);
        // The literal that used to be the silent default for a missing header AND the 4-eyes
        // carve-out — so it could not mean anything, and is now unwritable.
        assertThat(actor).isNotEqualToIgnoringCase(AuditActors.LEGACY_SYSTEM);
    }

    @Test
    @DisplayName("an attested channel cannot be used to mint a reserved system/service principal")
    void reservedNamespaceForgery_isDowngraded() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        // A caller trying to launder a platform principal through a channel it legitimately holds.
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "system:auto-suspend");

        String actor = resolver().resolve(req);

        assertThat(actor).isEqualTo("unverified:system:auto-suspend");
        assertThat(AuditActors.isSystem(actor)).isFalse();
        assertThat(AuditActors.isAttributable(actor)).isFalse();
    }

    @Test
    @DisplayName("a blank secret means nobody can be attested — honest, and it says so")
    void unconfiguredSecret_attestsNobody() {
        AuthAuditActorResolver noSecret = new AuthAuditActorResolver("", false, false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");

        assertThat(noSecret.resolve(req)).isEqualTo("unverified:alice@gme.com");
    }

    @Test
    @DisplayName("require-attestation=true refuses the write instead of recording a bare claim")
    void requireAttestation_failsClosed() {
        AuthAuditActorResolver strict = new AuthAuditActorResolver(SECRET, true, false);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");

        assertThatThrownBy(() -> strict.resolve(req))
                .isInstanceOf(AuthAuditActorResolver.UnattestedActorException.class);
    }

    @Test
    @DisplayName("one request resolves to one actor, even if its headers change mid-request")
    void resolutionIsMemoisedPerRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(RbacHeaders.PRINCIPAL_ID, "alice@gme.com");
        AuthAuditActorResolver r = resolver();

        String first = r.resolve(req);
        // Simulate a later read after something tried to upgrade the request's credentials.
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        assertThat(r.resolve(req)).isEqualTo(first).isEqualTo("unverified:alice@gme.com");
    }

    // ── IP ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the recorded IP is the transport peer, not the client-supplied X-Forwarded-For")
    void forwardedForIsNotTrustedByDefault() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        req.addHeader("X-Forwarded-For", "203.0.113.9");
        req.setRemoteAddr("10.1.2.3");

        // An unauthenticated X-Forwarded-For is free text; trusting it by default would mean the
        // one column recording where an action came from records whatever the actor typed.
        assertThat(resolver().resolveIp(req)).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("X-Forwarded-For is honoured only when explicitly enabled AND the caller proved itself")
    void forwardedForIsHonouredWhenEnabledForAttestedCallers() {
        AuthAuditActorResolver trusting = new AuthAuditActorResolver(SECRET, false, true);

        MockHttpServletRequest attested = new MockHttpServletRequest();
        attested.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        attested.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        attested.setRemoteAddr("10.1.2.3");
        assertThat(trusting.resolveIp(attested)).isEqualTo("203.0.113.9");

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        anonymous.addHeader("X-Forwarded-For", "203.0.113.9");
        anonymous.setRemoteAddr("10.1.2.3");
        assertThat(trusting.resolveIp(anonymous)).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("no bound request at all → unattributed, not an invented system principal")
    void noRequestContext_isUnattributed() {
        // Defensive: another test in this JVM may have left a request bound to this thread.
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();

        // A scheduler or a slice test. Guessing "system:something" here would recreate the exact
        // bug T5-1 is about, in a new place.
        assertThat(resolver().currentActor()).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(resolver().currentIp()).isNull();
    }
}
