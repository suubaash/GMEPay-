package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * T0-2 regression: auth-identity's {@code /internal/**} surface (API-key issuance included) is now
 * behind the internal-auth gate and auth-identity refuses to boot without a secret, so self-serve
 * SANDBOX key issuance must present {@code X-Gme-Internal} exactly the way this client's
 * auth-identity siblings ({@link RestRbacAdminClient}, {@link RestApprovalQueueClient},
 * {@link RestOperatorActionAuditClient}) already do. It did not, so every Get-Started key issuance
 * would have 401'd.
 *
 * <p>Bound to the same builder the production constructor uses
 * ({@link RestSandboxKeyClient#builderFor}), so this asserts the real default-header wiring.
 */
class RestSandboxKeyClientInternalAuthTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final String ISSUED_JSON = """
            {"keyId":"pk_test_abc123def456","secretPlaintext":"sk_test_zzz999",
             "prefix":"pk_test_abc1","environment":"SANDBOX",
             "createdAt":"2026-07-03T10:15:30Z","expiresAt":null}
            """;

    @Test
    @DisplayName("issue presents the configured internal token (else auth-identity 401s)")
    void issueCarriesTheInternalToken() {
        RestClient.Builder b = RestSandboxKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestSandboxKeyClient client = new RestSandboxKeyClient(b.build());

        server.expect(requestTo(containsString("/internal/auth/keys")))
                .andExpect(method(POST))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(ISSUED_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.issue("42", "my first key").keyId()).isEqualTo("pk_test_abc123def456");
        server.verify();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-closed, never a fabricated credential")
    void blankSecretSendsNoToken() {
        RestClient.Builder b = RestSandboxKeyClient.builderFor("http://auth-identity:8080", "");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestSandboxKeyClient client = new RestSandboxKeyClient(b.build());

        server.expect(requestTo(containsString("/internal/auth/keys")))
                .andExpect(method(POST))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess(ISSUED_JSON, MediaType.APPLICATION_JSON));

        client.issue("42", "my first key");
        server.verify();
    }
}
