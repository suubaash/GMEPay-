package com.gme.pay.gateway.partner;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Test-source-set replacement for the deleted {@code StubPartnerCredentialService} (T0-7).
 *
 * <p>The production stub is gone, not gated: it held api keys and HMAC secrets that were published
 * in this repository and it was the <em>default</em> credential source in every environment. These
 * values live in {@code src/test} only — they are never on the runtime classpath, never packaged
 * into the service jar, and no shipped bean can resolve them. Keeping the same literals here is
 * deliberate: the pre-existing filter tests assert behaviour against them, and their continued
 * presence in tests is what proves the shipped build no longer accepts them
 * ({@code PartnerCredentialConfigTest#publishedStubKeysAuthenticateNothing}).
 */
public final class TestPartnerCredentials {

    /** SHA-256 fingerprint (lower-case hex) of the fixture mTLS certificate. */
    public static final String MTLS_FINGERPRINT =
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";

    /** The api key the deleted production stub published. Must authenticate nothing at runtime. */
    public static final String PUBLISHED_API_KEY = "pk_test_abc";

    /** The HMAC secret the deleted production stub published. */
    public static final String PUBLISHED_SECRET = "sk_test_xyz";

    /** The second published pair (a partner with no registered mTLS certificate). */
    public static final String PUBLISHED_API_KEY_NO_MTLS = "pk_test_no_mtls";

    private static final Map<String, PartnerCredentials> STORE = Map.of(
            PUBLISHED_API_KEY, new PartnerCredentials(
                    "partner_test_001",
                    PUBLISHED_API_KEY,
                    PUBLISHED_SECRET,
                    List.of(),
                    PartnerCredentials.PartnerType.OVERSEAS,
                    300,
                    MTLS_FINGERPRINT),
            PUBLISHED_API_KEY_NO_MTLS, new PartnerCredentials(
                    "partner_test_002",
                    PUBLISHED_API_KEY_NO_MTLS,
                    "sk_test_no_mtls",
                    List.of(),
                    PartnerCredentials.PartnerType.OVERSEAS,
                    300,
                    null));

    private TestPartnerCredentials() {
    }

    /** An in-memory {@link PartnerCredentialService} over the fixture rows. */
    public static PartnerCredentialService service() {
        return apiKey -> {
            PartnerCredentials creds = apiKey == null ? null : STORE.get(apiKey);
            return creds == null ? Mono.empty() : Mono.just(creds);
        };
    }

    /** The fixture row for an api key, or {@code null}. */
    public static PartnerCredentials get(String apiKey) {
        return STORE.get(apiKey);
    }
}
