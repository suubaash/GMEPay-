package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.SandboxKeyClient.IssuedSandboxKey;
import com.gme.pay.bff.client.SandboxKeyClient.SandboxKeyView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestSandboxKeyClient} maps auth-identity's internal
 * key-issuance contract ({@code POST/GET /internal/auth/keys}) onto the Partner
 * Portal's {@code SandboxKeyClient} shape, and pins the SANDBOX-scoping
 * guarantees:
 *
 * <ol>
 *   <li>issue POSTs {@code environment=SANDBOX}, {@code purpose=API} and the
 *       {@code pk_test_}/{@code sk_test_} test prefixes — a sandbox key is never
 *       issued as PRODUCTION;</li>
 *   <li>the one-time plaintext + prefix + createdAt map through, and
 *       auth-identity's {@code environment} maps to the portal's {@code scope};</li>
 *   <li>list forwards {@code partnerId} + {@code environment=SANDBOX} and returns
 *       secret-free views.</li>
 * </ol>
 */
class RestSandboxKeyClientTest {

    private MockRestServiceServer server;

    private RestSandboxKeyClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestSandboxKeyClient(builder.build());
    }

    @Test
    void issue_postsSandboxScopedRequest_mapsPlaintextAndScope() {
        RestSandboxKeyClient client = newClient();
        String body = """
                {"keyId":"pk_test_abc123def456","secretPlaintext":"sk_test_zzz999",
                 "prefix":"pk_test_abc1","environment":"SANDBOX",
                 "createdAt":"2026-07-03T10:15:30Z","expiresAt":null}
                """;
        server.expect(requestTo(containsString("/internal/auth/keys")))
                .andExpect(method(POST))
                // SANDBOX-scoping is asserted on the OUTBOUND request body:
                .andExpect(jsonPath("$.environment").value("SANDBOX"))
                .andExpect(jsonPath("$.purpose").value("API"))
                .andExpect(jsonPath("$.keyPrefix").value("pk_test_"))
                .andExpect(jsonPath("$.secretPrefix").value("sk_test_"))
                .andExpect(jsonPath("$.partnerId").value(42))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        IssuedSandboxKey issued = client.issue("42", "my first key");
        server.verify();

        assertThat(issued.keyId()).isEqualTo("pk_test_abc123def456");
        assertThat(issued.apiKey()).isEqualTo("sk_test_zzz999");
        assertThat(issued.prefix()).isEqualTo("pk_test_abc1");
        // auth-identity environment -> portal scope, always SANDBOX here.
        assertThat(issued.scope()).isEqualTo("SANDBOX");
        assertThat(issued.createdAt()).isNotNull();
        // The one-time plaintext must not leak through toString().
        assertThat(issued.toString()).contains("REDACTED").doesNotContain(issued.apiKey());
    }

    @Test
    void issue_missingEnvironmentInResponse_defaultsToSandboxScope() {
        RestSandboxKeyClient client = newClient();
        // Defensive: even if auth-identity omitted environment, the portal scope
        // must still read SANDBOX (a sandbox key can never be labelled otherwise).
        String body = """
                {"keyId":"pk_test_x","secretPlaintext":"sk_test_y","prefix":"pk_test_x",
                 "createdAt":"2026-07-03T10:15:30Z"}
                """;
        server.expect(requestTo(containsString("/internal/auth/keys")))
                .andExpect(method(POST))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        IssuedSandboxKey issued = client.issue("7", null);
        server.verify();
        assertThat(issued.scope()).isEqualTo("SANDBOX");
    }

    @Test
    void list_forwardsSandboxFilter_returnsSecretFreeViews() {
        RestSandboxKeyClient client = newClient();
        String body = """
                [{"keyId":"pk_test_abc123def456","prefix":"pk_test_abc1",
                  "environment":"SANDBOX","createdAt":"2026-07-03T10:15:30Z"},
                 {"keyId":"pk_test_ghi789jkl012","prefix":"pk_test_ghi7",
                  "environment":"SANDBOX","createdAt":"2026-07-02T09:00:00Z"}]
                """;
        server.expect(requestTo(containsString("/internal/auth/keys")))
                .andExpect(requestTo(containsString("partnerId=42")))
                .andExpect(requestTo(containsString("environment=SANDBOX")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SandboxKeyView> views = client.listForPartner("42");
        server.verify();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).keyId()).isEqualTo("pk_test_abc123def456");
        assertThat(views.get(0).prefix()).isEqualTo("pk_test_abc1");
        assertThat(views.get(0).scope()).isEqualTo("SANDBOX");
        assertThat(views.get(0).createdAt()).isNotNull();
        // No accessor on the view exposes secret material; the record's field set
        // is (keyId, prefix, scope, createdAt) — no plaintext can ride through.
    }

    @Test
    void list_nonNumericPartner_returnsEmptyWithoutCallingUpstream() {
        // auth-identity keys hang off a numeric partner surrogate; a non-numeric
        // path variable has nothing to list and must NOT hit the network.
        RestSandboxKeyClient client = new RestSandboxKeyClient(RestClient.builder().build());
        assertThat(client.listForPartner("partner_test_001")).isEmpty();
    }
}
