package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
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
 * T0-5 / T0-2 regression: prefunding's whole balance API — including
 * {@code GET /v1/prefunding/{code}/{balance,alerts}}, the two routes the ops partner-balance panel
 * reads — is behind the service-to-service internal-auth gate, so this client MUST present
 * {@code X-Gme-Internal} or every panel read 401s.
 *
 * <p>Both cases bind {@link MockRestServiceServer} to the <b>same builder the production
 * constructor uses</b> ({@link RestPrefundingClient#builderFor}), so a pass is evidence about the
 * real default-header wiring rather than about a hand-built client.
 */
class RestPrefundingClientInternalAuthTest {

    /** Test fixture, not a credential — never read outside the test source set. */
    private static final String INTERNAL_TOKEN = "fixture-token-not-a-deployment-secret";

    private static final String BASE = "http://prefunding:8080";

    private static final String BALANCE_JSON = """
            {"partnerCode":"GMEREMIT","currency":"USD","balance":"1000.00","threshold":"100.00",
             "pctOfThreshold":"1000.00","recentDeductions":null}
            """;

    @Test
    @DisplayName("balance read presents the configured internal token (else prefunding 401s)")
    void balanceCarriesTheInternalToken() {
        RestClient.Builder b = RestPrefundingClient.builderFor(BASE, INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPrefundingClient client = new RestPrefundingClient(b.build());

        server.expect(requestTo(BASE + "/v1/prefunding/GMEREMIT/balance"))
                .andExpect(method(GET))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess(BALANCE_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getAdminBalance("GMEREMIT")).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("alerts read presents the configured internal token too")
    void alertsCarryTheInternalToken() {
        RestClient.Builder b = RestPrefundingClient.builderFor(BASE, INTERNAL_TOKEN);
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPrefundingClient client = new RestPrefundingClient(b.build());

        server.expect(requestTo(BASE + "/v1/prefunding/GMEREMIT/alerts"))
                .andExpect(method(GET))
                .andExpect(header(InternalAuthHeaders.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getBalanceAlerts("GMEREMIT")).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a blank secret sends NO token — fail-closed (prefunding refuses), never a bypass")
    void blankSecretSendsNoToken() {
        RestClient.Builder b = RestPrefundingClient.builderFor(BASE, "   ");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPrefundingClient client = new RestPrefundingClient(b.build());

        server.expect(requestTo(BASE + "/v1/prefunding/GMEREMIT/balance"))
                .andExpect(headerDoesNotExist(InternalAuthHeaders.INTERNAL_TOKEN))
                .andRespond(withSuccess(BALANCE_JSON, MediaType.APPLICATION_JSON));

        client.getAdminBalance("GMEREMIT");
        server.verify();
    }
}
