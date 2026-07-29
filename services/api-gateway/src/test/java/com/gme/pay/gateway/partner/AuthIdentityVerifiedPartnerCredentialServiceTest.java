package com.gme.pay.gateway.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.gateway.partner.AuthIdentityCredentialStatusClient.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * T0-7 — a key is accepted only if the gateway holds signing material for it <b>and</b>
 * auth-identity's {@code api_keys} store says it is live.
 */
class AuthIdentityVerifiedPartnerCredentialServiceTest {

    private static final String REAL_KEY = "pk_live_realkeyfromissuance";
    private static final String REAL_SECRET = "sk_live_onetimeplaintextfromissuance";
    private static final String BOGUS_KEY = "pk_live_neverissued";

    /** The operator-configured signing-material table, holding exactly one real partner. */
    private static ConfigPartnerCredentialService configuredSource() {
        ConfigPartnerCredentialProperties props = new ConfigPartnerCredentialProperties();
        ConfigPartnerCredentialProperties.PartnerEntry e =
                new ConfigPartnerCredentialProperties.PartnerEntry();
        e.setApiKey(REAL_KEY);
        e.setPartnerId("GMEREMIT");
        e.setHmacSecret(REAL_SECRET);
        e.setIpCidrRanges(List.of("203.0.113.0/24"));
        props.setPartners(List.of(e));
        return new ConfigPartnerCredentialService(props);
    }

    /** A status client with no network at all, returning a pinned verdict. */
    private static final class FixedStatusClient extends AuthIdentityCredentialStatusClient {
        private final Status status;
        private final AtomicInteger calls = new AtomicInteger();

        FixedStatusClient(Status status) {
            super(WebClient.builder().exchangeFunction(
                    r -> Mono.just(ClientResponse.create(HttpStatus.OK).build())).build(), true);
            this.status = status;
        }

        @Override
        public Mono<Status> statusOf(String apiKey) {
            calls.incrementAndGet();
            return Mono.just(status);
        }

        int calls() {
            return calls.get();
        }
    }

    /** A status client that always reports the store as unreachable. */
    private static final class UnavailableStatusClient extends AuthIdentityCredentialStatusClient {
        UnavailableStatusClient() {
            super(WebClient.builder().exchangeFunction(
                    r -> Mono.just(ClientResponse.create(HttpStatus.OK).build())).build(), true);
        }

        @Override
        public Mono<Status> statusOf(String apiKey) {
            return Mono.error(new PartnerCredentialSourceUnavailableException("store down"));
        }
    }

    @Test
    @DisplayName("a real issued key that is ACTIVE upstream → resolves, with its signing material")
    void realActiveKeyResolves() {
        FixedStatusClient statusClient = new FixedStatusClient(Status.ACTIVE);
        PartnerCredentialService svc =
                new AuthIdentityVerifiedPartnerCredentialService(configuredSource(), statusClient);

        PartnerCredentials creds = svc.findByApiKey(REAL_KEY).block();

        assertThat(creds).isNotNull();
        assertThat(creds.partnerId()).isEqualTo("GMEREMIT");
        assertThat(creds.apiSecretHmacKey()).isEqualTo(REAL_SECRET);
        assertThat(creds.ipCidrRanges()).containsExactly("203.0.113.0/24");
        assertThat(statusClient.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bogus key → empty (401), and auth-identity is never asked about it")
    void bogusKeyIsRejectedWithoutProbingUpstream() {
        FixedStatusClient statusClient = new FixedStatusClient(Status.ACTIVE);
        PartnerCredentialService svc =
                new AuthIdentityVerifiedPartnerCredentialService(configuredSource(), statusClient);

        assertThat(svc.findByApiKey(BOGUS_KEY).blockOptional()).isEmpty();
        assertThat(svc.findByApiKey(null).blockOptional()).isEmpty();
        assertThat(statusClient.calls())
                .as("an unauthenticated caller must not be able to probe the credential store")
                .isZero();
    }

    @Test
    @DisplayName("REVOKED upstream → empty (401) even though the key is still in gateway config")
    void revokedUpstreamIsRejected() {
        PartnerCredentialService svc = new AuthIdentityVerifiedPartnerCredentialService(
                configuredSource(), new FixedStatusClient(Status.INACTIVE));

        assertThat(svc.findByApiKey(REAL_KEY).blockOptional())
                .as("revocation must be effective at the edge")
                .isEmpty();
    }

    @Test
    @DisplayName("unknown upstream → empty (401): a key the platform never issued cannot authenticate")
    void unknownUpstreamIsRejected() {
        PartnerCredentialService svc = new AuthIdentityVerifiedPartnerCredentialService(
                configuredSource(), new FixedStatusClient(Status.UNKNOWN));

        assertThat(svc.findByApiKey(REAL_KEY).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("credential store unreachable → error (503), never an accept and never a silent 401")
    void unavailableStoreFailsClosed() {
        PartnerCredentialService svc = new AuthIdentityVerifiedPartnerCredentialService(
                configuredSource(), new UnavailableStatusClient());

        assertThatThrownBy(() -> svc.findByApiKey(REAL_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
    }

    @Test
    @DisplayName("a config row with a blank hmac-secret is not usable (no empty-key HMAC)")
    void blankSecretRowIsDropped() {
        ConfigPartnerCredentialProperties props = new ConfigPartnerCredentialProperties();
        ConfigPartnerCredentialProperties.PartnerEntry e =
                new ConfigPartnerCredentialProperties.PartnerEntry();
        e.setApiKey("pk_live_nosecret");
        e.setPartnerId("GMEREMIT");
        e.setHmacSecret("   ");
        props.setPartners(List.of(e));

        assertThat(new ConfigPartnerCredentialService(props)
                .findByApiKey("pk_live_nosecret").blockOptional()).isEmpty();
    }
}
