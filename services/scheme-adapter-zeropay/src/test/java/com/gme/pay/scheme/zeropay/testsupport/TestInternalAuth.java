package com.gme.pay.scheme.zeropay.testsupport;

/**
 * Test helper for the service-to-service internal-auth gate that fronts every
 * {@code /internal/scheme/zeropay} endpoint (T0-2).
 *
 * <p>{@link #SECRET} must match {@code gmepay.internal-auth.secret} in
 * {@code src/test/resources/application-test.properties}. It is a fixture, not a credential —
 * nothing outside the test source set reads that file.
 */
public final class TestInternalAuth {

    /** The shared internal token used by the {@code test} profile. */
    public static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private TestInternalAuth() {}
}
