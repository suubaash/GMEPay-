package com.gme.pay.gateway.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.gateway.partner.AuthIdentityCredentialStatusClient.Status;
import com.gme.pay.internalauth.InternalAuthHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * T0-7 — the gateway's binding to the <b>real</b> credential store, and its fail-closed behaviour
 * when that store cannot answer.
 *
 * <p>The distinction this class exists to pin: an <em>unknown key</em> is a decision (401, the
 * partner should stop), an <em>unavailable store</em> is the absence of one (503, retryable). Before
 * T0-7 the gateway consulted no store at all, so neither existed — a key was valid forever once it
 * was compiled in.
 */
class AuthIdentityCredentialStatusClientTest {

    private static final String API_KEY = "pk_live_realkeyfromissuance";
    private static final String INTERNAL_SECRET = "test-fixture-internal-token";

    private final List<ClientRequest> captured = new ArrayList<>();

    private AuthIdentityCredentialStatusClient clientAnswering(HttpStatus status, String body) {
        ExchangeFunction fn = request -> {
            captured.add(request);
            ClientResponse.Builder b = ClientResponse.create(status)
                    .header("Content-Type", "application/json");
            if (body != null) {
                b.body(body);
            }
            return Mono.just(b.build());
        };
        return new AuthIdentityCredentialStatusClient(
                WebClient.builder().exchangeFunction(fn),
                "http://auth-identity:8080",
                INTERNAL_SECRET);
    }

    // ------------------------------------------------------------- real answers

    @Test
    @DisplayName("found + active → ACTIVE (a key auth-identity really issued and has not revoked)")
    void foundAndActive() {
        assertThat(clientAnswering(HttpStatus.OK,
                "{\"found\":true,\"active\":true,\"partnerId\":42}").statusOf(API_KEY).block())
                .isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("found but not active → INACTIVE (revoked or expired ⇒ the edge stops accepting it)")
    void foundButRevoked() {
        assertThat(clientAnswering(HttpStatus.OK,
                "{\"found\":true,\"active\":false,\"partnerId\":42}").statusOf(API_KEY).block())
                .isEqualTo(Status.INACTIVE);
    }

    @Test
    @DisplayName("not found → UNKNOWN (never issued by this platform)")
    void notFound() {
        assertThat(clientAnswering(HttpStatus.OK,
                "{\"found\":false,\"active\":false,\"partnerId\":null}").statusOf(API_KEY).block())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("a body missing its fields degrades to UNKNOWN (deny), never to ACTIVE")
    void malformedBodyDeniesRatherThanAllows() {
        assertThat(clientAnswering(HttpStatus.OK, "{}").statusOf(API_KEY).block())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("a blank api key is UNKNOWN without a round-trip")
    void blankApiKeyShortCircuits() {
        AuthIdentityCredentialStatusClient client =
                clientAnswering(HttpStatus.OK, "{\"found\":true,\"active\":true}");
        assertThat(client.statusOf("  ").block()).isEqualTo(Status.UNKNOWN);
        assertThat(client.statusOf(null).block()).isEqualTo(Status.UNKNOWN);
        assertThat(captured).as("no lookup should have been issued").isEmpty();
    }

    // --------------------------------------------------------- fail closed (503)

    @Test
    @DisplayName("credential store 5xx → UNAVAILABLE, not 'unknown key' and not a pass")
    void serverErrorFailsClosed() {
        assertThatThrownBy(() -> clientAnswering(HttpStatus.INTERNAL_SERVER_ERROR, "boom")
                .statusOf(API_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
    }

    @Test
    @DisplayName("credential store 401 (bad internal token) → UNAVAILABLE, never 'key is fine'")
    void unauthorizedFailsClosed() {
        assertThatThrownBy(() ->
                clientAnswering(HttpStatus.UNAUTHORIZED, "{}").statusOf(API_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
    }

    @Test
    @DisplayName("credential store unreachable (transport failure) → UNAVAILABLE")
    void transportFailureFailsClosed() {
        AuthIdentityCredentialStatusClient client = new AuthIdentityCredentialStatusClient(
                WebClient.builder().exchangeFunction(
                        request -> Mono.error(new java.net.ConnectException("connection refused"))),
                "http://auth-identity:8080",
                INTERNAL_SECRET);

        assertThatThrownBy(() -> client.statusOf(API_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
    }

    @Test
    @DisplayName("2xx with an empty body → UNAVAILABLE (no answer is not the same as 'unknown')")
    void emptyBodyFailsClosed() {
        assertThatThrownBy(() -> clientAnswering(HttpStatus.OK, null).statusOf(API_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class);
    }

    @Test
    @DisplayName("no internal-auth secret configured → UNAVAILABLE without a round-trip")
    void blankInternalSecretFailsClosed() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        AuthIdentityCredentialStatusClient client = new AuthIdentityCredentialStatusClient(
                WebClient.builder().exchangeFunction(request -> {
                    called.set(true);
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                }),
                "http://auth-identity:8080",
                "   ");

        assertThatThrownBy(() -> client.statusOf(API_KEY).block())
                .isInstanceOf(PartnerCredentialSourceUnavailableException.class)
                .hasMessageContaining("no internal-auth token configured")
                .hasMessageContaining("GMEPAY_INTERNAL_AUTH_SECRET");
        assertThat(called.get())
                .as("a blank token must not be sent as if it were a credential")
                .isFalse();
    }

    // ----------------------------------------------------------------- the wire

    @Test
    @DisplayName("the lookup presents X-Gme-Internal and POSTs the documented resolve endpoint")
    void presentsInternalTokenOnTheRealEndpoint() {
        clientAnswering(HttpStatus.OK, "{\"found\":true,\"active\":true,\"partnerId\":42}")
                .statusOf(API_KEY).block();

        assertThat(captured).hasSize(1);
        ClientRequest request = captured.get(0);
        assertThat(request.url().getPath()).isEqualTo("/internal/auth/keys/resolve");
        assertThat(request.method().name()).isEqualTo("POST");
        assertThat(request.headers().getFirst(InternalAuthHeaders.INTERNAL_TOKEN))
                .isEqualTo(INTERNAL_SECRET);
    }
}
