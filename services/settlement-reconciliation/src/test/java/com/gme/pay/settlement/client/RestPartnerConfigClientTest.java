package com.gme.pay.settlement.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gme.pay.settlement.port.PartnerConfigPort.PartnerSettlementConfig;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Maps config-registry PartnerView → settlement config; fails soft to defaults. */
class RestPartnerConfigClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final RestPartnerConfigClient client =
            new RestPartnerConfigClient(restTemplate, "http://config-registry:8081");

    @Test
    @DisplayName("maps settlementRoundingMode + settle currency from PartnerView")
    void mapsPartnerView() {
        server.expect(requestTo("http://config-registry:8081/v1/partners/GMEREMIT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"partnerCode\":\"GMEREMIT\",\"settlementCurrency\":\"KRW\","
                                + "\"settlementRoundingMode\":\"FLOOR\",\"settleACcy\":\"KRW\"}",
                        MediaType.APPLICATION_JSON));

        PartnerSettlementConfig cfg = client.resolve("GMEREMIT");

        assertEquals("KRW", cfg.settleCcy());
        assertEquals(RoundingMode.FLOOR, cfg.mode());
        server.verify();
    }

    @Test
    @DisplayName("config-registry error → safe defaults (HALF_UP / KRW), never throws")
    void defaultsOnError() {
        server.expect(requestTo("http://config-registry:8081/v1/partners/UNKNOWN"))
                .andRespond(withServerError());

        PartnerSettlementConfig cfg = client.resolve("UNKNOWN");

        assertEquals("KRW", cfg.settleCcy());
        assertEquals(RoundingMode.HALF_UP, cfg.mode());
    }
}
