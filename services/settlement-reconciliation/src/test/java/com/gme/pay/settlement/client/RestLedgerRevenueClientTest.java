package com.gme.pay.settlement.client;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GETs revenue-ledger {@code /v1/revenue} with the numeric partner id resolved from the date's own
 * batches ({@code partner_id_new}) and a single-day {@code startDate=endDate} window (the endpoint
 * has no date-only form); returns {@code totalServiceChargeAmount}. Fails soft to {@code null}
 * (ledger leg unavailable) on transport error, missing numeric partner id, or a non-KRW fee.
 */
class RestLedgerRevenueClientTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);
    private static final String EXPECTED_URL =
            "http://revenue-ledger:8084/v1/revenue?partnerId=7&startDate=2026-06-15&endDate=2026-06-15";

    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final SettlementBatchRepository batchRepository = mock(SettlementBatchRepository.class);
    private final RestLedgerRevenueClient client =
            new RestLedgerRevenueClient(restTemplate, "http://revenue-ledger:8084", batchRepository);

    @Test
    @DisplayName("resolves partnerIdNew from the date's batches, queries the single-day window, returns the KRW fee")
    void returnsServiceChargeForDate() {
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batchWithPartnerId(7L)));
        server.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(summaryJson("2000", "KRW"), MediaType.APPLICATION_JSON));

        BigDecimal fee = client.revenueFor(DATE);

        assertThat(fee).isEqualByComparingTo("2000");
        server.verify();
    }

    @Test
    @DisplayName("no batch carries a numeric partner id → null without any HTTP call")
    void noNumericPartnerIdMeansUnavailable() {
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batchWithPartnerId(null)));
        // No server.expect(...) → MockRestServiceServer fails if any request is made.

        assertThat(client.revenueFor(DATE)).isNull();
        server.verify();
    }

    @Test
    @DisplayName("revenue-ledger error → null (ledger leg unavailable), never throws")
    void failsSoftOnError() {
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batchWithPartnerId(7L)));
        server.expect(requestTo(EXPECTED_URL)).andRespond(withServerError());

        assertThat(client.revenueFor(DATE)).isNull();
    }

    @Test
    @DisplayName("non-zero fee in a non-KRW currency cannot tie out cent-for-cent → null")
    void nonKrwFeeMeansUnavailable() {
        when(batchRepository.findByBusinessDate(DATE)).thenReturn(List.of(batchWithPartnerId(7L)));
        server.expect(requestTo(EXPECTED_URL))
                .andRespond(withSuccess(summaryJson("13.57", "USD"), MediaType.APPLICATION_JSON));

        assertThat(client.revenueFor(DATE)).isNull();
    }

    // ------------------------------------------------------------------ fixtures

    private static SettlementBatchEntity batchWithPartnerId(Long partnerIdNew) {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                "ZP0061-20260615-MORNING", "ZEROPAY", DATE, "GENERATED",
                new BigDecimal("100000"), "KRW", Instant.parse("2026-06-15T05:00:00Z"));
        batch.setPartnerIdNew(partnerIdNew);
        return batch;
    }

    /** Canonical RevenueSummaryView wire shape — money as decimal STRINGs (MONEY_CONVENTION.md). */
    private static String summaryJson(String serviceCharge, String ccy) {
        return """
                {
                  "partnerId": 7,
                  "schemeId": 0,
                  "startDate": "2026-06-15",
                  "endDate": "2026-06-15",
                  "txnCount": 3,
                  "totalFxMarginUsd": "0",
                  "totalServiceChargeAmount": "%s",
                  "serviceChargeCcy": "%s",
                  "totalRoundingUsd": "0"
                }
                """.formatted(serviceCharge, ccy);
    }
}
