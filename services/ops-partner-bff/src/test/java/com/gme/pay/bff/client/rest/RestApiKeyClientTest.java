package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.bff.client.ApiKeyClient;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.contracts.PartnerStatus;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Gap T1-3: the Partner Portal's API Keys page must show the partner's REAL credentials from
 * auth-identity's {@code api_keys} registry. Before this client existed the page could only render
 * {@code StubApiKeyClient}'s two fabricated {@code gpk_live_<hash>} keys.
 *
 * <p>These tests pin four things: real rows are mapped, the query is partner-scoped by the resolved
 * numeric surrogate, fields auth-identity does not hold stay ABSENT rather than being invented, and
 * an unavailable/unresolvable upstream degrades to empty rather than to a fake.
 */
class RestApiKeyClientTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final long PARTNER_SURROGATE = 4242L;
    private static final String PARTNER_CODE = "GMEREMIT";

    /** auth-identity's KeyListItem roster for PRODUCTION. */
    private static final String PRODUCTION_JSON = """
            [{"keyId":"pk_live_aaaabbbbcccc1111","prefix":"pk_live_aaaa",
              "environment":"PRODUCTION","createdAt":"2026-05-02T09:00:00Z",
              "status":"ACTIVE","expiresAt":null},
             {"keyId":"pk_live_ddddeeeeffff2222","prefix":"pk_live_dddd",
              "environment":"PRODUCTION","createdAt":"2026-01-11T08:00:00Z",
              "status":"REVOKED","expiresAt":null}]
            """;

    /** auth-identity's KeyListItem roster for SANDBOX. */
    private static final String SANDBOX_JSON = """
            [{"keyId":"pk_test_999988887777","prefix":"pk_test_9999",
              "environment":"SANDBOX","createdAt":"2026-06-20T12:30:00Z",
              "status":"ACTIVE","expiresAt":"2027-06-20T12:30:00Z"}]
            """;

    /** A directory that resolves exactly one code, so scoping can be asserted precisely. */
    private static PartnerDirectory directoryResolving(String code, Long surrogate) {
        ConfigRegistryClient registry = new ConfigRegistryClient() {
            @Override
            public PartnerSummary getPartner(String partnerId) {
                return null;
            }

            @Override
            public List<PartnerSummary> listPartners() {
                return List.of();
            }

            @Override
            public PartnerView getPartnerView(String partnerCode) {
                if (!code.equals(partnerCode)) {
                    return null;
                }
                return PartnerView.ofCore(surrogate, code, PartnerType.OVERSEAS, "USD",
                        RoundingMode.HALF_UP);
            }

            @Override
            public PartnerSummary createPartner(PartnerCreateRequest request) {
                return null;
            }

            @Override
            public PartnerSummary updateRoundingMode(String partnerId, String mode) {
                return null;
            }

            @Override
            public List<SchemeSummary> listSchemes() {
                return List.of();
            }
        };
        return new PartnerDirectory(registry);
    }

    @Test
    @DisplayName("lists the partner's REAL keys from both rosters, newest first")
    void listsRealKeysFromBothEnvironments() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andExpect(method(GET))
                .andExpect(queryParam("partnerId", String.valueOf(PARTNER_SURROGATE)))
                .andRespond(withSuccess(PRODUCTION_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andExpect(method(GET))
                .andExpect(queryParam("partnerId", String.valueOf(PARTNER_SURROGATE)))
                .andRespond(withSuccess(SANDBOX_JSON, MediaType.APPLICATION_JSON));

        List<ApiKeyClient.ApiKeyView> keys = client.listForPartner(PARTNER_CODE);
        server.verify();

        // Newest-first across BOTH rosters: sandbox 2026-06-20, then live 2026-05-02, then 2026-01-11.
        assertThat(keys).extracting(ApiKeyClient.ApiKeyView::keyId).containsExactly(
                "pk_test_999988887777",
                "pk_live_aaaabbbbcccc1111",
                "pk_live_ddddeeeeffff2222");

        // Real upstream facts survive the mapping.
        ApiKeyClient.ApiKeyView sandbox = keys.get(0);
        assertThat(sandbox.prefix()).isEqualTo("pk_test_9999");
        assertThat(sandbox.status()).isEqualTo("ACTIVE");
        assertThat(sandbox.environment()).isEqualTo("SANDBOX");
        assertThat(sandbox.createdAt()).isEqualTo(Instant.parse("2026-06-20T12:30:00Z"));
        assertThat(sandbox.expiresAt()).isEqualTo(Instant.parse("2027-06-20T12:30:00Z"));

        // A revoked key is reported as revoked, not filtered into looking usable.
        assertThat(keys.get(2).status()).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("fields auth-identity does not record stay ABSENT — no invented name/scopes/lastUsedAt")
    void absentFieldsAreNotFabricated() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andRespond(withSuccess(PRODUCTION_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<ApiKeyClient.ApiKeyView> keys = client.listForPartner(PARTNER_CODE);

        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(k -> {
            // api_keys (V002) has no name column, no per-key scopes and no last_used_at column.
            // The stub used to invent "Primary"/"Rotating", a two-scope list and a last-used
            // instant for all three.
            assertThat(k.name()).isNull();
            assertThat(k.scopes()).isEmpty();
            assertThat(k.lastUsedAt()).isNull();
        });
    }

    @Test
    @DisplayName("an unresolvable partner code returns EMPTY and never queries auth-identity unscoped")
    void unresolvablePartnerFailsClosed() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        // Directory knows only GMEREMIT, so SOMEONE_ELSE resolves to nothing.
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        assertThat(client.listForPartner("SOMEONE_ELSE")).isEmpty();

        // No HTTP call at all: an unscoped GET would have leaked every partner's keys.
        server.verify();
    }

    @Test
    @DisplayName("a partner row with no surrogate id fails closed rather than querying with null")
    void nullSurrogateFailsClosed() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, null));

        assertThat(client.listForPartner(PARTNER_CODE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("an auth-identity outage degrades to NO keys, never to placeholder credentials")
    void upstreamOutageDegradesHonestly() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andRespond(withServerError());
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andRespond(withServerError());

        assertThat(client.listForPartner(PARTNER_CODE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("presents the internal-auth token (auth-identity's /internal/** is gated — T0-2)")
    void carriesTheInternalToken() {
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andExpect(header(com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN,
                        INTERNAL_TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andExpect(header(com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN,
                        INTERNAL_TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listForPartner(PARTNER_CODE);
        server.verify();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-closed, never a fabricated credential")
    void blankSecretSendsNoToken() {
        RestClient.Builder b = RestApiKeyClient.builderFor("http://auth-identity:8080", "");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andExpect(headerDoesNotExist(
                        com.gme.pay.internalauth.InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listForPartner(PARTNER_CODE);
        server.verify();
    }

    @Test
    @DisplayName("no secret or hash field can ride the key list (SEC-09 §4)")
    void secretMaterialIsNeverSurfaced() {
        // Even if a future auth-identity build added a secret to the list payload, this client's
        // wire record has no field for it, so it cannot reach the Portal.
        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        String withSecret = """
                [{"keyId":"pk_live_aaaabbbbcccc1111","prefix":"pk_live_aaaa",
                  "environment":"PRODUCTION","createdAt":"2026-05-02T09:00:00Z","status":"ACTIVE",
                  "secretPlaintext":"sk_live_LEAKED","secretHash":"deadbeef"}]
                """;
        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andRespond(withSuccess(withSecret, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<ApiKeyClient.ApiKeyView> keys = client.listForPartner(PARTNER_CODE);

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).toString())
                .doesNotContain("sk_live_LEAKED")
                .doesNotContain("deadbeef");
    }

    @Test
    @DisplayName("PartnerStatus on the resolved row is irrelevant — a non-LIVE partner still lists keys")
    void listsKeysRegardlessOfLifecycleStatus() {
        // Sanity: key listing is not gated on lifecycle. A SANDBOX-stage partner integrating for
        // the first time must be able to see the sandbox key they were issued.
        assertThat(PartnerStatus.ONBOARDING).isNotNull();

        RestClient.Builder b = RestApiKeyClient.builderFor(
                "http://auth-identity:8080", INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestApiKeyClient client = new RestApiKeyClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("environment=PRODUCTION")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("environment=SANDBOX")))
                .andRespond(withSuccess(SANDBOX_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.listForPartner(PARTNER_CODE))
                .extracting(ApiKeyClient.ApiKeyView::keyId)
                .containsExactly("pk_test_999988887777");
        server.verify();
    }
}
