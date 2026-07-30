package com.gme.pay.registry.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.internalauth.InternalAuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The security property of gap T5-1, stated as tests: <b>a forged or absent actor header cannot
 * produce an audited action attributed to a real principal.</b>
 *
 * <p>Before this change, {@code X-Actor} was unauthenticated client input that landed verbatim in
 * {@code audit_log.actor_id} — or, when omitted, landed as the literal {@code "system"}, which was
 * also the blanket carve-out in the 4-eyes CHECK. Both of those are asserted impossible here.
 */
class AuditActorResolutionTest {

    private static final String SECRET = TestActors.INTERNAL_SECRET;

    @Test
    @DisplayName("a forged X-Actor with no verified credential is recorded as a CLAIM, not a principal")
    void forgedHeaderIsNotAttributed() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners/X/kyb");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "ceo@gme.com");

        String actor = resolver.resolve(request);

        assertThat(actor)
                .as("the claim is preserved for forensics...")
                .contains("ceo@gme.com")
                .as("...but is structurally distinguishable from the real principal")
                .isEqualTo("unverified:ceo@gme.com")
                .isNotEqualTo("ceo@gme.com");
        assertThat(AuditActors.isAttributable(actor)).isFalse();
    }

    @Test
    @DisplayName("an absent X-Actor is 'unattributed', never 'system'")
    void absentHeaderIsNeverSystem() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        String actor = resolver.resolve(new MockHttpServletRequest("POST", "/v1/partners"));

        assertThat(actor).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(actor)
                .as("the old default was the literal 'system', which the 4-eyes CHECK exempts — "
                        + "so a header-less propose plus a header-less approve self-approved")
                .isNotEqualTo("system");
        assertThat(AuditActors.isAttributable(actor)).isFalse();
    }

    @Test
    @DisplayName("a claim from a caller that proved itself with the internal secret IS attested")
    void attestedCallerGetsTheRealPrincipal() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        String actor = resolver.resolve(request);

        assertThat(actor).isEqualTo("alice@gme.com");
        assertThat(AuditActors.isAttributable(actor)).isTrue();
    }

    @Test
    @DisplayName("a WRONG internal token buys nothing — it is not 'close enough'")
    void wrongInternalTokenIsNotAttestation() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET + "x");

        assertThat(resolver.resolve(request)).isEqualTo("unverified:alice@gme.com");
    }

    @Test
    @DisplayName("with NO secret configured nothing can be attested — the service says so rather than pretending")
    void noConfiguredSecretMeansNothingIsAttested() {
        AuditActorResolver resolver = new AuditActorResolver("", false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, "anything");

        assertThat(resolver.resolve(request)).isEqualTo("unverified:alice@gme.com");
    }

    @Test
    @DisplayName("an attested caller cannot forge a system principal through the trusted channel")
    void attestedCallerCannotMintASystemPrincipal() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "system:auto-suspend");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        String actor = resolver.resolve(request);

        assertThat(AuditActors.isSystem(actor))
                .as("forwarding a reserved namespace over an attested channel must not mint a "
                        + "system principal")
                .isFalse();
        assertThat(actor).isEqualTo("unverified:system:auto-suspend");
    }

    @Test
    @DisplayName("an attested caller with no human principal is recorded as the SERVICE, not as a human")
    void attestedCallerWithoutClaimIsAServiceIdentity() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        String actor = resolver.resolve(request);

        assertThat(actor).startsWith(AuditActors.SERVICE_PREFIX);
        assertThat(AuditActors.isAttributable(actor))
                .as("we genuinely know a trusted service acted; we just do not know which human")
                .isTrue();
    }

    @Test
    @DisplayName("require-attestation refuses the write outright instead of recording an unproven one")
    void strictModeRefusesUnattestedWrites() {
        AuditActorResolver strict = new AuditActorResolver(SECRET, true, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");

        assertThatThrownBy(() -> strict.resolve(request))
                .isInstanceOf(UnattestedActorException.class);

        // ...and the same request WITH the token is fine.
        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/v1/partners");
        ok.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");
        ok.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        assertThat(strict.resolve(ok)).isEqualTo("alice@gme.com");
    }

    @Test
    @DisplayName("one request resolves to exactly one actor, even if asked twice")
    void resolutionIsStablePerRequest() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/partners");
        request.addHeader(AuditActorResolver.ACTOR_HEADER, "alice@gme.com");

        String first = resolver.resolve(request);
        // A second call must not re-read the headers — otherwise a request could produce two audit
        // rows attributed to two different actors if a header were mutated mid-request.
        request.removeHeader(AuditActorResolver.ACTOR_HEADER);
        assertThat(resolver.resolve(request)).isEqualTo(first);
    }

    @Test
    @DisplayName("actor_ip is the transport peer address, not the client-supplied X-Forwarded-For")
    void actorIpIgnoresForwardedForFromAnUntrustedCaller() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ops/pause");
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertThat(resolver.resolveIp(request))
                .as("OpsControlService used to record X-Forwarded-For verbatim, so an operator "
                        + "could write any IP they liked into the audit row")
                .isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("X-Forwarded-For is honoured only from an attested caller with the flag on")
    void forwardedForIsHonouredOnlyWhenAttestedAndEnabled() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ops/pause");
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);

        assertThat(new AuditActorResolver(SECRET, false, true).resolveIp(request))
                .isEqualTo("203.0.113.7");

        MockHttpServletRequest sameButUntrusted = new MockHttpServletRequest("POST", "/v1/ops/pause");
        sameButUntrusted.setRemoteAddr("10.1.2.3");
        sameButUntrusted.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(new AuditActorResolver(SECRET, false, true).resolveIp(sameButUntrusted))
                .as("the flag alone is not enough — the caller must also be attested")
                .isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("off-request resolution is 'unattributed', not an invented system principal")
    void offRequestIsUnattributed() {
        AuditActorResolver resolver = new AuditActorResolver(SECRET, false, false);
        assertThat(resolver.resolve(null)).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(resolver.resolveIp(null)).isNull();
        // The static accessor used by PartnerStore behaves the same with no request in scope.
        assertThat(AuditActorResolver.currentRequestActor()).isEqualTo(AuditActors.UNATTRIBUTED);
        assertThat(AuditActorResolver.currentRequestIp()).isNull();
    }
}
