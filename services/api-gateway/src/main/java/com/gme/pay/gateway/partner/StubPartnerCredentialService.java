package com.gme.pay.gateway.partner;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Phase-1 stub implementation of {@link PartnerCredentialService}.
 *
 * <p>Credentials are hard-coded for test partners only. In production this is replaced by
 * {@code PostgresPartnerCredentialService} (R2DBC + Redis cache, from T18). This bean exists
 * so filters can be exercised without a running database.
 *
 * <p>Registered test partners:
 * <ul>
 *   <li>{@code pk_test_abc} / secret {@code sk_test_xyz} — OVERSEAS, no IP restriction</li>
 * </ul>
 */
@Service
public class StubPartnerCredentialService implements PartnerCredentialService {

    /**
     * SHA-256 fingerprint (lower-case hex) of the stub mTLS certificate used in tests.
     * Matches the value hardcoded in {@code MtlsFingerprintFilterTest}.
     */
    public static final String STUB_MTLS_FINGERPRINT =
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";

    private static final Map<String, PartnerCredentials> STORE = Map.of(
            "pk_test_abc", new PartnerCredentials(
                    "partner_test_001",
                    "pk_test_abc",
                    "sk_test_xyz",
                    List.of(),
                    PartnerCredentials.PartnerType.OVERSEAS,
                    300,
                    STUB_MTLS_FINGERPRINT),
            "pk_test_no_mtls", new PartnerCredentials(
                    "partner_test_002",
                    "pk_test_no_mtls",
                    "sk_test_no_mtls",
                    List.of(),
                    PartnerCredentials.PartnerType.OVERSEAS,
                    300,
                    null));

    @Override
    public Mono<PartnerCredentials> findByApiKey(String apiKey) {
        PartnerCredentials creds = STORE.get(apiKey);
        return creds == null ? Mono.empty() : Mono.just(creds);
    }
}
