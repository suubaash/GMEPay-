package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.client.SmartRouterClient.PartnerSchemeView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture network→candidate map checks, incl. the Phase-2 SendMN routing: a classified
 * qpay / mn.qpay / sendmn network resolves to the SENDMN candidate.
 */
class FixtureSmartRouterClientTest {

    private final FixtureSmartRouterClient client = new FixtureSmartRouterClient();

    @Test
    @DisplayName("qpay / mn.qpay / sendmn networks resolve to the SENDMN candidate")
    void qpayResolvesToSendmn() {
        for (String network : new String[]{"qpay", "mn.qpay", "sendmn"}) {
            List<PartnerSchemeView> candidates = client.resolve(network, "MN", "MPM", "OVERSEAS");
            assertEquals(1, candidates.size(), network);
            assertEquals("SENDMN", candidates.get(0).schemeId(), network);
        }
    }

    @Test
    @DisplayName("existing ZeroPay / Nepal mappings are unchanged")
    void existingMappingsUnchanged() {
        assertEquals("zeropay", client.resolve("com.zeropay", "KR", "MPM", "DOMESTIC").get(0).schemeId());
        assertEquals("NEPAL", client.resolve("fonepay.com", "NP", "MPM", "OVERSEAS").get(0).schemeId());
    }

    @Test
    @DisplayName("unknown network resolves to no candidates")
    void unknownNetworkEmpty() {
        assertTrue(client.resolve("mystery.net", null, "MPM", "OVERSEAS").isEmpty());
    }
}
