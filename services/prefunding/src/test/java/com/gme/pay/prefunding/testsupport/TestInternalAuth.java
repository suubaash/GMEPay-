package com.gme.pay.prefunding.testsupport;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Test helper for the service-to-service internal-auth gate that fronts every prefunding endpoint
 * (T0-5 / CISO#6).
 *
 * <p>{@link #SECRET} must match {@code gmepay.internal-auth.secret} in
 * {@code src/test/resources/application-test.properties}. It is a fixture, not a credential —
 * nothing reads that file outside the test source set.
 *
 * <p>Existing API tests exercise business behaviour <em>through</em> the live gate by stamping the
 * token via {@link #authed(MockHttpServletRequestBuilder)}; the gate's own behaviour (no token /
 * wrong token → 401) is proved separately in
 * {@code com.gme.pay.prefunding.api.InternalAuthGateTest}.
 */
public final class TestInternalAuth {

    /** The shared internal token used by the test profile. */
    public static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private TestInternalAuth() {}

    /** Stamps the trusted-internal-caller token onto a request builder and returns it. */
    public static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder rb) {
        return rb.header(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
    }
}
