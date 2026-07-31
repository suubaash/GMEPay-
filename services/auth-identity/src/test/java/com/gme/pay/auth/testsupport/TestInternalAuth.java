package com.gme.pay.auth.testsupport;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.springframework.http.HttpHeaders;

/**
 * Test helper for the service-to-service internal-auth gate that fronts every auth-identity
 * endpoint (T0-2 / #90).
 *
 * <p>{@link #SECRET} must match {@code gmepay.internal-auth.secret} in
 * {@code src/test/resources/application-test.properties}. It is a fixture, not a credential —
 * nothing outside the test source set reads that file.
 */
public final class TestInternalAuth {

    /** The shared internal token used by the {@code test} profile. */
    public static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private TestInternalAuth() {}

    /** Headers carrying the trusted-internal-caller token. */
    public static HttpHeaders authed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalAuthHeaders.INTERNAL_TOKEN, SECRET);
        return headers;
    }
}
