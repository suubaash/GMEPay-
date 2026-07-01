package com.gme.pay.router.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockRestServiceServer} wire tests for {@link RestPartnerSchemeRegistry}
 * (config-registry NOT running): the live partner_scheme JSON maps to
 * {@link PartnerSchemeRecord} rows, and the resolver's three data branches
 * (NO_SCHEME_FOR_LOCATION / DIRECTION_NOT_ENABLED / PAYMENT_MODE_NOT_SUPPORTED)
 * behave correctly over the FETCHED data.
 */
class RestPartnerSchemeRegistryTest {

    private static final String BASE = "http://config-registry:8080";

    private MockRestServiceServer server;
    private RestPartnerSchemeRegistry registry;
    private LocationSchemeResolver resolver;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        registry = new RestPartnerSchemeRegistry(builder.build());
        resolver = new LocationSchemeResolver(registry);
    }

    /** Stage the KR directory + one partner's KH/KR scheme rows. */
    private void stageKrCorridor() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"id":1,"partnerCode":"KRBANK","status":"LIVE",
                           "operatingAddress":{"city":"Seoul","country":"KR"}},
                          {"id":2,"partnerCode":"VNPAY","status":"LIVE",
                           "operatingAddress":{"country":"VN"}},
                          {"id":3,"partnerCode":"KRSUSP","status":"SUSPENDED",
                           "operatingAddress":{"country":"KR"}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRBANK/schemes"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"partnerId":1,"schemeId":"ZEROPAY","direction":"BOTH",
                           "enabled":true,"countryCode":"KR","supportsCpm":true,
                           "supportsMpm":true,"priority":1,"status":"ACTIVE"},
                          {"partnerId":1,"schemeId":"GIRO_CPM","direction":"OUTBOUND",
                           "enabled":true,"countryCode":"KR","supportsCpm":true,
                           "supportsMpm":false,"priority":0,"status":"ACTIVE"},
                          {"partnerId":1,"schemeId":"DISABLED_ROW","direction":"BOTH",
                           "enabled":false,"countryCode":"KR","supportsCpm":true,
                           "supportsMpm":true,"priority":2,"status":"ACTIVE"},
                          {"partnerId":1,"schemeId":"SUSPENDED_ROW","direction":"BOTH",
                           "enabled":true,"countryCode":"KR","supportsCpm":true,
                           "supportsMpm":true,"priority":3,"status":"SUSPENDED"}
                        ]
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("maps live PartnerSchemeView JSON to records: enabled+active+country-matched, priority-ordered")
    void mapsViewJsonToRecords() {
        stageKrCorridor();

        List<PartnerSchemeRecord> rows = registry.schemesForCountry("kr");

        // VNPAY (other country) + KRSUSP (suspended partner) skipped; the
        // disabled and non-ACTIVE rows dropped; remaining sorted by priority.
        assertEquals(List.of("GIRO_CPM", "ZEROPAY"),
                rows.stream().map(PartnerSchemeRecord::schemeId).toList());
        PartnerSchemeRecord giro = rows.get(0);
        assertEquals("KR", giro.countryCode());
        assertEquals("OUTBOUND", giro.direction());
        assertTrue(giro.cpmSupported());
        server.verify();
    }

    @Test
    @DisplayName("resolve over live data picks priority winner for a supported mode+direction")
    void resolveSuccessOverLiveData() {
        stageKrCorridor();

        SchemeResolution res = resolver.resolve(
                new LocationSchemeQuery("KR", PaymentMode.CPM, "OUTBOUND"));

        // OUTBOUND+CPM matches GIRO_CPM (priority 0) and ZEROPAY (BOTH/CPM).
        assertEquals("GIRO_CPM", res.scheme());
        server.verify();
    }

    @Test
    @DisplayName("NO_SCHEME_FOR_LOCATION: directory has no partner in the country")
    void noSchemeForLocationBranch() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess(
                        "[{\"id\":2,\"partnerCode\":\"VNPAY\",\"status\":\"LIVE\","
                                + "\"operatingAddress\":{\"country\":\"VN\"}}]",
                        MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class,
                () -> resolver.resolve(new LocationSchemeQuery("KR", PaymentMode.CPM, "INBOUND")));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("DIRECTION_NOT_ENABLED: KR rows are all OUTBOUND, INBOUND requested")
    void directionNotEnabledBranch() {
        // All rows OUTBOUND-only (no BOTH), so an INBOUND request finds none.
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"partnerCode\":\"KRBANK\",\"status\":\"LIVE\","
                                + "\"operatingAddress\":{\"country\":\"KR\"}}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRBANK/schemes"))
                .andRespond(withSuccess("""
                        [{"partnerId":1,"schemeId":"GIRO_CPM","direction":"OUTBOUND",
                          "enabled":true,"countryCode":"KR","supportsCpm":true,
                          "supportsMpm":true,"priority":0,"status":"ACTIVE"}]
                        """, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class,
                () -> resolver.resolve(new LocationSchemeQuery("KR", PaymentMode.CPM, "INBOUND")));
        assertEquals(ErrorCode.DIRECTION_NOT_ENABLED, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("PAYMENT_MODE_NOT_SUPPORTED: OUTBOUND row matches direction but is MPM-only request")
    void paymentModeNotSupportedBranch() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"partnerCode\":\"KRBANK\",\"status\":\"LIVE\","
                                + "\"operatingAddress\":{\"country\":\"KR\"}}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRBANK/schemes"))
                .andRespond(withSuccess("""
                        [{"partnerId":1,"schemeId":"GIRO_CPM","direction":"OUTBOUND",
                          "enabled":true,"countryCode":"KR","supportsCpm":true,
                          "supportsMpm":false,"priority":0,"status":"ACTIVE"}]
                        """, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class,
                () -> resolver.resolve(new LocationSchemeQuery("KR", PaymentMode.MPM, "OUTBOUND")));
        assertEquals(ErrorCode.PAYMENT_MODE_NOT_SUPPORTED, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("mode support falls back to approvalMethod* presence when flags are null")
    void modeSupportFallsBackToApprovalMethods() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"partnerCode\":\"KRBANK\",\"status\":\"LIVE\","
                                + "\"operatingAddress\":{\"country\":\"KR\"}}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRBANK/schemes"))
                .andRespond(withSuccess("""
                        [{"partnerId":1,"schemeId":"ZEROPAY","direction":"BOTH",
                          "enabled":true,"countryCode":"KR","approvalMethodMpm":"CONFIRMATION"}]
                        """, MediaType.APPLICATION_JSON));

        List<PartnerSchemeRecord> rows = registry.schemesForCountry("KR");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).mpmSupported());
        assertTrue(!rows.get(0).cpmSupported());
        server.verify();
    }

    @Test
    @DisplayName("ADR-016: networkIdentifier CSV + partnerId flow through to the ordered candidate list")
    void networkCandidatesOverLiveData() {
        // NP directory with two partners, both serving fonepay.com; the second at a
        // higher priority. Proves CSV membership match + ordered multi-candidate.
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess("""
                        [
                          {"id":20,"partnerCode":"NEPAL_PSP","status":"LIVE",
                           "operatingAddress":{"country":"NP"}},
                          {"id":21,"partnerCode":"FONEPAY_DIRECT","status":"LIVE",
                           "operatingAddress":{"country":"NP"}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/NEPAL_PSP/schemes"))
                .andRespond(withSuccess("""
                        [{"partnerId":20,"schemeId":"NEPAL","direction":"BOTH","enabled":true,
                          "countryCode":"NP","supportsCpm":true,"supportsMpm":true,"priority":0,
                          "status":"ACTIVE","networkIdentifier":"fonepay.com,nepalpay,com.f1soft"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/FONEPAY_DIRECT/schemes"))
                .andRespond(withSuccess("""
                        [{"partnerId":21,"schemeId":"NEPAL_FONEPAY_DIRECT","direction":"BOTH",
                          "enabled":true,"countryCode":"NP","supportsCpm":true,"supportsMpm":true,
                          "priority":1,"status":"ACTIVE","networkIdentifier":"fonepay.com"}]
                        """, MediaType.APPLICATION_JSON));

        var candidates = resolver.resolveCandidates("fonepay.com",
                new LocationSchemeQuery("NP", PaymentMode.MPM, "DOMESTIC"));

        assertEquals(List.of("NEPAL", "NEPAL_FONEPAY_DIRECT"),
                candidates.stream().map(c -> c.schemeId()).toList());
        assertEquals(20L, candidates.get(0).partnerId());
        assertEquals("fonepay.com,nepalpay,com.f1soft", candidates.get(0).networkIdentifier());
        server.verify();
    }

    @Test
    @DisplayName("an upstream 5xx surfaces as SCHEME_UNAVAILABLE, never a silent empty")
    void upstreamFailureMapsToSchemeUnavailable() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withServerError());

        ApiException ex = assertThrows(ApiException.class, () -> registry.schemesForCountry("KR"));
        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }
}
