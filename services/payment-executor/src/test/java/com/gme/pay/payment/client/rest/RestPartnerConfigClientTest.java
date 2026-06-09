package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.PaymentException;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** {@link MockRestServiceServer} unit tests for {@link RestPartnerConfigClient}. */
class RestPartnerConfigClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestPartnerConfigClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://config-registry:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPartnerConfigClient(builder.build());
    }

    @Test
    @DisplayName("loadPartner parses response and maps roundingMode string to RoundingMode enum")
    void loadPartner_parsesAndMapsRoundingMode() {
        server.expect(requestTo("http://config-registry:8080/v1/partners/P-001"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"partnerId\":\"P-001\",\"type\":\"OVERSEAS\","
                                + "\"settlementCurrency\":\"USD\","
                                + "\"settlementRoundingMode\":\"DOWN\"}",
                        MediaType.APPLICATION_JSON));

        PartnerConfigClient.PartnerConfigView view = client.loadPartner("P-001");

        assertEquals("P-001", view.partnerId());
        assertEquals("OVERSEAS", view.type());
        assertEquals("USD", view.settlementCurrency());
        assertEquals(RoundingMode.DOWN, view.settlementRoundingMode());
        server.verify();
    }

    @Test
    @DisplayName("non-2xx response is wrapped in PaymentException")
    void loadPartner_nonSuccessThrowsPaymentException() {
        server.expect(requestTo("http://config-registry:8080/v1/partners/P-MISSING"))
                .andRespond(withServerError().body("boom"));

        assertThrows(PaymentException.class, () -> client.loadPartner("P-MISSING"));
        server.verify();
    }
}
