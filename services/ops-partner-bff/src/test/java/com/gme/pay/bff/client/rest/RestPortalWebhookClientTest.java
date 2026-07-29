package com.gme.pay.bff.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PartnerDirectory;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import com.gme.pay.contracts.PartnerView;
import com.gme.pay.domain.PartnerType;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Gap T1-3: the Portal's Webhooks page must read the partner's REAL endpoints from
 * notification-webhook. It previously rendered two rows built inline in
 * {@code PartnerPortalController} pointing at {@code partner.example.com}, both {@code ACTIVE}, with
 * a literal {@code 2026-06-09T11:00:00Z} last-delivery — identical for every partner, and never
 * sourced from the service that actually delivers webhooks.
 */
class RestPortalWebhookClientTest {

    private static final long PARTNER_SURROGATE = 77L;
    private static final String PARTNER_CODE = "SENDMN";

    private static final String CONFIGS_JSON = """
            {"configs":[
               {"id":11,"partnerId":77,"webhookUrl":"https://ops.sendmn.mn/gmepay/payments",
                "eventTypes":["payment.approved","payment.failed"],"active":true,
                "createdAt":"2026-04-01T00:00:00Z","updatedAt":"2026-06-02T10:00:00Z"},
               {"id":12,"partnerId":77,"webhookUrl":"https://ops.sendmn.mn/gmepay/settlements",
                "eventTypes":null,"active":true,
                "createdAt":"2026-04-01T00:00:00Z","updatedAt":null}],
             "count":2}
            """;

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
                return code.equals(partnerCode)
                        ? PartnerView.ofCore(surrogate, code, PartnerType.OVERSEAS, "USD",
                                RoundingMode.HALF_UP)
                        : null;
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
    @DisplayName("maps the partner's REAL registered endpoints, scoped by numeric surrogate")
    void mapsRealEndpoints() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andExpect(method(GET))
                .andExpect(queryParam("partnerId", String.valueOf(PARTNER_SURROGATE)))
                .andRespond(withSuccess(CONFIGS_JSON, MediaType.APPLICATION_JSON));

        List<WebhookConfigView> rows = client.listForPartner(PARTNER_CODE);
        server.verify();

        assertThat(rows).extracting(WebhookConfigView::url).containsExactly(
                "https://ops.sendmn.mn/gmepay/payments",
                "https://ops.sendmn.mn/gmepay/settlements");
        assertThat(rows.get(0).eventTypes())
                .containsExactly("payment.approved", "payment.failed");
        assertThat(rows.get(0).status()).isEqualTo("ACTIVE");

        // Nothing from the removed inline fixture may survive.
        assertThat(rows).noneSatisfy(r ->
                assertThat(r.url()).contains("partner.example.com"));
    }

    @Test
    @DisplayName("lastDeliveredAt is ABSENT — notification-webhook has no per-endpoint last-delivery read")
    void lastDeliveredAtIsAbsentNotInvented() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andRespond(withSuccess(CONFIGS_JSON, MediaType.APPLICATION_JSON));

        List<WebhookConfigView> rows = client.listForPartner(PARTNER_CODE);

        // The inline stub printed a literal Instant here for every partner. There is no real source,
        // so it must be null and render as an em dash.
        assertThat(rows).allSatisfy(r -> assertThat(r.lastDeliveredAt()).isNull());
    }

    @Test
    @DisplayName("null upstream eventTypes ('all events') surfaces as empty, not an invented roster")
    void nullEventTypesBecomeEmpty() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andRespond(withSuccess(CONFIGS_JSON, MediaType.APPLICATION_JSON));

        List<WebhookConfigView> rows = client.listForPartner(PARTNER_CODE);
        assertThat(rows.get(1).eventTypes()).isEmpty();
    }

    @Test
    @DisplayName("a deactivated endpoint is reported INACTIVE, not silently shown as ACTIVE")
    void inactiveEndpointIsLabelled() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andRespond(withSuccess("""
                        {"configs":[{"id":9,"partnerId":77,
                          "webhookUrl":"https://ops.sendmn.mn/old","eventTypes":[],
                          "active":false,"createdAt":"2026-01-01T00:00:00Z","updatedAt":null}],
                         "count":1}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listForPartner(PARTNER_CODE))
                .singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo("INACTIVE"));
    }

    @Test
    @DisplayName("an unresolvable partner code returns EMPTY and issues no unscoped query")
    void unresolvablePartnerFailsClosed() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        assertThat(client.listForPartner("NOT_A_PARTNER")).isEmpty();
        server.verify(); // no HTTP call: an unscoped GET would list every partner's endpoints
    }

    @Test
    @DisplayName("a notification-webhook outage degrades to NO endpoints, never to fixtures")
    void upstreamOutageDegradesHonestly() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andRespond(withServerError());

        assertThat(client.listForPartner(PARTNER_CODE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("no signing secret can ride the webhook list")
    void secretMaterialIsNeverSurfaced() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://notification-webhook:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestPortalWebhookClient client = new RestPortalWebhookClient(
                b.build(), directoryResolving(PARTNER_CODE, PARTNER_SURROGATE));

        server.expect(requestTo(containsString("/v1/webhook-configs")))
                .andRespond(withSuccess("""
                        {"configs":[{"id":9,"partnerId":77,"webhookUrl":"https://ops.sendmn.mn/x",
                          "eventTypes":[],"active":true,"createdAt":"2026-01-01T00:00:00Z",
                          "signingSecret":"whsec_LEAKED","signingSecretHash":"deadbeef"}],
                         "count":1}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listForPartner(PARTNER_CODE).toString())
                .doesNotContain("whsec_LEAKED")
                .doesNotContain("deadbeef");
    }
}
