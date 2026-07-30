package com.gme.pay.registry.actor;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

/**
 * Test support for the T5-1 actor-attestation rules.
 *
 * <h2>Why the existing controller tests needed this</h2>
 *
 * <p>The standalone MockMvc setups in this service build controllers directly, with no Spring
 * container, so {@link ActorWebConfig} never registers {@link AuditActorArgumentResolver}. Without
 * it a {@link AuditActorHeader} parameter falls through to Spring's default simple-type resolution
 * (treated as an absent request parameter) and arrives as {@code null} — which would have made
 * every standalone test exercise the "no identity at all" path regardless of the headers it sent.
 *
 * <p>{@link #withActorResolution} registers the real resolver with a known internal secret, so
 * those tests exercise the production rule:
 * <ul>
 *   <li>{@link #attested} sends the internal token alongside {@code X-Actor}, so the claim is
 *       accepted as a verified principal — the behaviour the ops BFF gets once it presents the
 *       token, and what the pre-existing assertions (e.g. {@code $.proposedBy == "maker_kim"})
 *       describe;</li>
 *   <li>sending {@code X-Actor} <i>without</i> the token models the forged/unauthenticated caller
 *       and must land as {@code unverified:<claim>} — see {@code AuditActorResolutionTest}.</li>
 * </ul>
 *
 * <p>The secret is a test literal and is deliberately not a realistic-looking value, so a
 * secret-scanner finding here is a true negative.
 */
public final class TestActors {

    /** The internal-auth secret the test resolver is configured with. */
    public static final String INTERNAL_SECRET = "test-internal-secret-not-a-real-credential";

    private TestActors() {}

    /** A resolver configured with {@link #INTERNAL_SECRET}, attestation not required. */
    public static AuditActorResolver resolver() {
        return new AuditActorResolver(INTERNAL_SECRET, false, false);
    }

    /** A resolver that REFUSES any unattested write ({@code require-attestation=true}). */
    public static AuditActorResolver strictResolver() {
        return new AuditActorResolver(INTERNAL_SECRET, true, false);
    }

    /** Register the production argument resolver on a standalone MockMvc builder. */
    public static StandaloneMockMvcBuilder withActorResolution(StandaloneMockMvcBuilder builder) {
        return builder.setCustomArgumentResolvers(new AuditActorArgumentResolver(resolver()));
    }

    /**
     * Present the internal-auth token so the {@code X-Actor} claim on this request is treated as
     * an attested principal. Chain after the {@code .header("X-Actor", …)} the test already sends.
     */
    public static MockHttpServletRequestBuilder attested(MockHttpServletRequestBuilder builder) {
        return builder.header(
                com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_SECRET);
    }
}
