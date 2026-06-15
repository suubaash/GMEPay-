package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.SettlementClient.SettlementBatchSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestSettlementClient} maps settlement-reconciliation's
 * {@code SettlementResponse} rows onto the BFF's {@link SettlementBatchSummary}
 * (synthetic batchId, merchant as partnerId, KRW net amount), filters by partner
 * client-side, and returns {@code null} for the unsupported per-batch detail.
 */
class RestSettlementClientTest {

    private MockRestServiceServer server;

    private RestSettlementClient newClient() {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestSettlementClient(builder.build());
    }

    @Test
    void recent_mapsSettlementRowsToBatchSummaries() {
        RestSettlementClient client = newClient();
        // settlement money rides as KRW integers (JSON numbers, not strings).
        String body = """
                [{"merchantId":"M-100","settlementDate":"2026-06-09","settlementType":"N",
                  "txnCount":3,"grossTxnAmount":175000,"merchantFeeTotal":1500,
                  "netSettlementAmount":173500},
                 {"merchantId":"M-200","settlementDate":"2026-06-09","settlementType":"G",
                  "txnCount":1,"grossTxnAmount":90000,"merchantFeeTotal":0,
                  "netSettlementAmount":90000}]
                """;
        server.expect(requestTo(containsString("/v1/settlements")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SettlementBatchSummary> batches = client.recent(null, 10);
        server.verify();

        assertThat(batches).hasSize(2);
        SettlementBatchSummary first = batches.get(0);
        assertThat(first.batchId()).isEqualTo("M-100-2026-06-09-N");
        assertThat(first.partnerId()).isEqualTo("M-100");
        assertThat(first.currency()).isEqualTo("KRW");
        assertThat(first.amount()).isEqualByComparingTo("173500");
        assertThat(first.status()).isEqualTo("COMPLETED");
    }

    @Test
    void recent_filtersByPartnerClientSide() {
        RestSettlementClient client = newClient();
        String body = """
                [{"merchantId":"M-100","settlementDate":"2026-06-09","settlementType":"N",
                  "txnCount":3,"grossTxnAmount":175000,"merchantFeeTotal":1500,
                  "netSettlementAmount":173500},
                 {"merchantId":"M-200","settlementDate":"2026-06-09","settlementType":"G",
                  "txnCount":1,"grossTxnAmount":90000,"merchantFeeTotal":0,
                  "netSettlementAmount":90000}]
                """;
        server.expect(requestTo(containsString("/v1/settlements")))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SettlementBatchSummary> batches = client.recent("M-200", 10);
        server.verify();

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).partnerId()).isEqualTo("M-200");
    }

    @Test
    void detail_returnsNull_noUpstreamEndpoint() {
        RestSettlementClient client = new RestSettlementClient(RestClient.builder().build());
        assertThat(client.detail("any-batch")).isNull();
    }
}
