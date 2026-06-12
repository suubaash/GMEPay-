package com.gme.pay.router.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link MockRestServiceServer} wire tests for {@link RestPartnerSchemeResolver}
 * (same pattern as prefunding's {@code RestConfigRegistryClientTest}):
 * per-partner reads hit {@code GET /v1/admin/partners/{code}/schemes} and keep
 * only ENABLED rows; a 404 maps to {@code NO_SCHEME_FOR_LOCATION}; country
 * reads scan {@code GET /v1/partners} and fan out per matching partner; any
 * other upstream failure is {@code SCHEME_UNAVAILABLE}.
 */
class RestPartnerSchemeResolverTest {

    private static final String BASE = "http://config-registry:8080";

    private MockRestServiceServer server;
    private RestPartnerSchemeResolver resolver;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        resolver = new RestPartnerSchemeResolver(builder.build());
    }

    @Test
    @DisplayName("resolveForPartner keeps enabled scheme ids only, in registry order")
    void resolveForPartner_returnsEnabledSchemeIds() {
        server.expect(requestTo(BASE + "/v1/admin/partners/GMEREMIT/schemes"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"partnerId":1,"schemeId":"ZEROPAY","direction":"BOTH","role":"ACQUIRER",
                           "zeropayMerchantId":"ZP-001","kftcInstitutionCode":"KFTC-9","enabled":true},
                          {"partnerId":1,"schemeId":"BAKONG","direction":"OUTBOUND","role":"ISSUER",
                           "enabled":false},
                          {"partnerId":1,"schemeId":"KHQR","direction":"BOTH","role":"BOTH",
                           "enabled":true}
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of("ZEROPAY", "KHQR"), resolver.resolveForPartner("GMEREMIT"));
        server.verify();
    }

    @Test
    @DisplayName("404 on the partner scheme lookup -> ApiException NO_SCHEME_FOR_LOCATION")
    void resolveForPartner_404MapsToNoSchemeForLocation() {
        server.expect(requestTo(BASE + "/v1/admin/partners/GHOST/schemes"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ApiException ex =
                assertThrows(ApiException.class, () -> resolver.resolveForPartner("GHOST"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
        server.verify();
    }

    @Test
    @DisplayName("country scan aggregates enabled schemes across matching, routable partners")
    void resolveForCountry_aggregatesAcrossPartners() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"id":1,"partnerCode":"KRBANK","status":"LIVE",
                           "operatingAddress":{"city":"Seoul","country":"KR"}},
                          {"id":2,"partnerCode":"KRWALLET","status":"ONBOARDING",
                           "countryOfIncorporation":"KR"},
                          {"id":3,"partnerCode":"VNPAY","status":"LIVE",
                           "operatingAddress":{"city":"Hanoi","country":"VN"}},
                          {"id":4,"partnerCode":"KRSUSPENDED","status":"SUSPENDED",
                           "operatingAddress":{"country":"KR"}}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRBANK/schemes"))
                .andRespond(withSuccess(
                        "[{\"partnerId\":1,\"schemeId\":\"ZEROPAY\",\"enabled\":true}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/admin/partners/KRWALLET/schemes"))
                .andRespond(withSuccess(
                        "[{\"partnerId\":2,\"schemeId\":\"ZEROPAY\",\"enabled\":true},"
                                + "{\"partnerId\":2,\"schemeId\":\"PROMPT_PAY\",\"enabled\":true}]",
                        MediaType.APPLICATION_JSON));

        // VNPAY (other country) and KRSUSPENDED (suspended) are never fanned
        // out to; duplicate ZEROPAY collapses, encounter order preserved.
        assertEquals(List.of("ZEROPAY", "PROMPT_PAY"), resolver.resolveForCountry("KR"));
        server.verify();
    }

    @Test
    @DisplayName("country with no matching partner yields an empty list (router turns it into 404)")
    void resolveForCountry_noMatchYieldsEmpty() {
        server.expect(requestTo(BASE + "/v1/partners"))
                .andRespond(withSuccess(
                        "[{\"id\":3,\"partnerCode\":\"VNPAY\",\"status\":\"LIVE\","
                                + "\"operatingAddress\":{\"country\":\"VN\"}}]",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(), resolver.resolveForCountry("KR"));
        server.verify();
    }

    @Test
    @DisplayName("an upstream 5xx surfaces as SCHEME_UNAVAILABLE, never a silent fallback")
    void upstreamFailure_mapsToSchemeUnavailable() {
        server.expect(requestTo(BASE + "/v1/admin/partners/GMEREMIT/schemes"))
                .andRespond(withServerError());

        ApiException ex =
                assertThrows(ApiException.class, () -> resolver.resolveForPartner("GMEREMIT"));
        assertEquals(ErrorCode.SCHEME_UNAVAILABLE, ex.errorCode());
        server.verify();
    }
}
