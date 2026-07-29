package com.gme.pay.kybadapter.testsupport;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Test fixture for kyb-adapter's internal-auth gate (T1-4).
 *
 * <p>{@link #SECRET} is a TEST FIXTURE, not a credential: it lives in the test
 * source set, is never packaged into the service jar, and exists only because the
 * service now refuses to boot without an armed gate
 * ({@code KybInternalAuthEnforcedConfig}). Every {@code @SpringBootTest} in this
 * module supplies it via
 * {@code @SpringBootTest(properties = "gmepay.internal-auth.secret=" + SECRET)}.
 */
public final class TestInternalAuth {

    /** The shared token the tests present; mirrors rate-fx's fixture discipline. */
    public static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    /** The property assignment a {@code @SpringBootTest} needs to boot the gated context. */
    public static final String SECRET_PROPERTY = "gmepay.internal-auth.secret=" + SECRET;

    private TestInternalAuth() {
    }

    /** Stamps the internal-auth header a gated route requires. */
    public static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder builder) {
        return builder.header(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
    }
}
