package com.gme.pay.bff.client.rest;

import com.gme.pay.bff.client.RevenueLedgerClient.RevenueBreakdown;
import com.gme.pay.bff.client.RevenueLedgerClient.RevenueSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies {@link RestRevenueLedgerClient} maps revenue-ledger's
 * {@code RevenueSummaryResponse} onto the BFF's {@link RevenueSummary}
 * (service-charge -> fee, fx-margin -> margin, sum -> total), queries the
 * configured aggregate partner with the right query string, and degrades
 * honestly to a zero summary / empty breakdown when no aggregate partner is set.
 */
class RestRevenueLedgerClientTest {

    private MockRestServiceServer server;

    private RestRevenueLedgerClient clientWithPartner(Long partnerId) {
        RestClient.Builder builder = RestClient.builder();
        this.server = MockRestServiceServer.bindTo(builder).build();
        return new RestRevenueLedgerClient(builder.build(), partnerId);
    }

    @Test
    void summaryRange_mapsWireFieldsAndSumsTotal() {
        RestRevenueLedgerClient client = clientWithPartner(42L);
        String body = """
                {"partnerId":42,"schemeId":7,"startDate":"2026-06-01","endDate":"2026-06-30",
                 "txnCount":120,"totalFxMarginUsd":"814.46","totalServiceChargeAmount":"420.10",
                 "serviceChargeCcy":"USD","totalRoundingUsd":"0.03"}
                """;
        server.expect(requestTo(containsString("/v1/revenue")))
                .andExpect(method(GET))
                .andExpect(queryParam("partnerId", "42"))
                .andExpect(queryParam("startDate", "2026-06-01"))
                .andExpect(queryParam("endDate", "2026-06-30"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RevenueSummary s = client.summaryRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        server.verify();

        assertThat(s).isNotNull();
        assertThat(s.date()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(s.feeRevenueUsd()).isEqualByComparingTo("420.10");
        assertThat(s.marginRevenueUsd()).isEqualByComparingTo("814.46");
        assertThat(s.totalRevenueUsd()).isEqualByComparingTo("1234.56");
    }

    @Test
    void getSummary_delegatesToSingleDayRange() {
        RestRevenueLedgerClient client = clientWithPartner(42L);
        String body = """
                {"partnerId":42,"schemeId":7,"startDate":"2026-06-15","endDate":"2026-06-15",
                 "txnCount":3,"totalFxMarginUsd":"10.00","totalServiceChargeAmount":"5.00",
                 "serviceChargeCcy":"USD"}
                """;
        server.expect(requestTo(containsString("/v1/revenue")))
                .andExpect(queryParam("startDate", "2026-06-15"))
                .andExpect(queryParam("endDate", "2026-06-15"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RevenueSummary s = client.getSummary(LocalDate.of(2026, 6, 15));
        server.verify();

        assertThat(s.totalRevenueUsd()).isEqualByComparingTo("15.00");
    }

    @Test
    void summaryRange_withoutAggregatePartner_returnsHonestZero() {
        // No MockRestServiceServer expectation: the client must NOT call upstream.
        RestRevenueLedgerClient client = new RestRevenueLedgerClient(RestClient.builder().build(), null);

        RevenueSummary s = client.summaryRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(s.date()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(s.totalRevenueUsd()).isEqualByComparingTo("0");
        assertThat(s.feeRevenueUsd()).isEqualByComparingTo("0");
        assertThat(s.marginRevenueUsd()).isEqualByComparingTo("0");
    }

    @Test
    void breakdown_isEmpty_noUpstreamMultiAxisEndpoint() {
        RestRevenueLedgerClient client = new RestRevenueLedgerClient(RestClient.builder().build(), 42L);
        RevenueBreakdown b = client.breakdown(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        assertThat(b.byPartner()).isEmpty();
        assertThat(b.byScheme()).isEmpty();
        assertThat(b.byCurrency()).isEmpty();
    }

    @Test
    void summaryRange_onUpstreamError_returnsZero() {
        RestRevenueLedgerClient client = clientWithPartner(42L);
        server.expect(requestTo(containsString("/v1/revenue")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON)); // empty body -> null

        RevenueSummary s = client.summaryRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        server.verify();

        assertThat(s.totalRevenueUsd()).isEqualByComparingTo("0");
    }
}
