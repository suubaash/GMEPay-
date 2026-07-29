package com.gme.pay.settlement.client;

import com.gme.pay.settlement.corridor.SchemeSettlementRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Leg (c) of the three-way tie-out reads the SendMN adapter's own settlement record: the hub
 * reference (the join key), the MNT paid, the SendMN-registered rate and the USD owed. Money arrives
 * as decimal strings and must survive as exact BigDecimals; an adapter outage must degrade to an
 * empty day rather than throw.
 */
class RestSendmnSettlementClientTest {

    private static final String BASE = "http://scheme-adapter-sendmn:8093";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final RestSendmnSettlementClient client = new RestSendmnSettlementClient(restTemplate, BASE);

    @Test
    @DisplayName("parses the adapter's daily settlement rows into exact BigDecimal amounts")
    void parsesDailyRows() {
        String body = """
                {
                  "date": "2026-07-28",
                  "status": "APPROVED",
                  "localCurCode": "MNT",
                  "settlementCurCode": "USD",
                  "latestRegisteredRate": "3280.000000",
                  "count": 1,
                  "rows": [
                    {
                      "hubReference": "SENDMN-abc",
                      "txTokenNo": "SMN20260728010000ABC",
                      "merchantId": "merchant-guid",
                      "localCurCode": "MNT",
                      "localAmount": "239440.00",
                      "fxTickerNo": "TICKER-9",
                      "fxUsdBuyRate": "3280.000000",
                      "settlementCurCode": "USD",
                      "settlementAmount": "73.0000",
                      "status": "APPROVED",
                      "paymentNo": "PN-1",
                      "paymentReceiptNo": "GME145",
                      "createdAt": "2026-07-28T01:00:00Z",
                      "updatedAt": "2026-07-28T01:00:05Z"
                    }
                  ]
                }
                """;
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/settlement/daily?date=2026-07-28"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SchemeSettlementRecord> records = client.confirmedOn(DATE);

        assertThat(client.scheme()).isEqualTo("SENDMN");
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.reference()).isEqualTo("SENDMN-abc");            // the tie-out join key
            assertThat(r.schemeRef()).isEqualTo("SMN20260728010000ABC");
            assertThat(r.merchantId()).isEqualTo("merchant-guid");
            assertThat(r.localAmount()).isEqualByComparingTo("239440.00");
            assertThat(r.registeredRate()).isEqualByComparingTo("3280.000000");
            assertThat(r.settlementUsd()).isEqualByComparingTo("73.0000");
            assertThat(r.settlementCcy()).isEqualTo("USD");
            assertThat(r.confirmedAt()).isEqualTo(Instant.parse("2026-07-28T01:00:00Z"));
        });
        server.verify();
    }

    @Test
    @DisplayName("adapter outage yields an empty day (breaks stay visible) instead of throwing")
    void adapterOutageDegradesToEmpty() {
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/settlement/daily?date=2026-07-28"))
                .andRespond(withServerError());

        assertThat(client.confirmedOn(DATE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a malformed amount on one row is treated as absent, not fatal")
    void malformedAmountIsAbsentNotFatal() {
        String body = """
                {
                  "date": "2026-07-28",
                  "rows": [
                    {
                      "hubReference": "SENDMN-abc",
                      "txTokenNo": "SMN-1",
                      "localAmount": "not-a-number",
                      "settlementAmount": "73.0000",
                      "status": "APPROVED"
                    }
                  ]
                }
                """;
        server.expect(requestTo(BASE + "/internal/scheme/sendmn/settlement/daily?date=2026-07-28"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<SchemeSettlementRecord> records = client.confirmedOn(DATE);

        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.localAmount()).isNull();
            assertThat(r.settlementUsd()).isEqualByComparingTo("73.0000");
        });
    }
}
