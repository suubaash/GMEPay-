package com.gme.pay.reporting;

import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.infrastructure.RestCommittedFxTransactionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link RestCommittedFxTransactionPort} using {@link MockRestServiceServer}
 * (mock HTTP — transaction-mgmt is NOT running). The mock returns the canonical
 * {@code List<CommittedFxView>} JSON shape from transaction-mgmt's
 * {@code GET /v1/transactions/fx-committed} projection.
 *
 * <p>Verifies the adapter:
 * <ul>
 *   <li>calls the correct URI ({@code /v1/transactions/fx-committed?from&to[&partnerId]});</li>
 *   <li>deserialises {@link com.gme.pay.contracts.CommittedFxView} JSON (decimal-string money/rates);</li>
 *   <li>maps the wire String {@code direction} to {@link TransactionDirection} via {@code valueOf};</li>
 *   <li>carries {@code offerRateColl} (BOK FX1015 #14) and {@code crossRate} VERBATIM (the gap fix);</li>
 *   <li>appends {@code partnerId} when provided; skips records with unknown direction;</li>
 *   <li>returns an empty list (never null) for an empty projection.</li>
 * </ul>
 */
class RestCommittedFxTransactionPortTest {

    private MockRestServiceServer mockServer;
    private RestCommittedFxTransactionPort port;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        RestClient restClient = RestClient.builder()
                .requestFactory(restTemplate.getRequestFactory())
                .baseUrl("http://transaction-mgmt:8080")
                .build();
        port = new RestCommittedFxTransactionPort(restClient);
    }

    @Test
    @DisplayName("fetchCommittedFx: maps CommittedFxView incl FX1015 #14 (offerRateColl) and direction valueOf")
    void fetchCommittedFx_mapsViewIncludingField14() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions/fx-committed"
                        + "?from=2026-05-20&to=2026-05-20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(twoViewsJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                port.fetchCommittedFx(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 20), null);

        mockServer.verify();
        assertEquals(2, result.size());

        // INBOUND → FX1015 carrier; field #14 must be carried verbatim from the projection.
        CommittedTransaction inbound = result.get(0);
        assertEquals("SCH-IN-001", inbound.getTxnRef());
        assertEquals(3001L, inbound.getTxnId());
        assertEquals(42L, inbound.getPartnerId());
        assertEquals(TransactionDirection.INBOUND, inbound.getDirection(),
                "wire String direction must be valueOf-mapped to the enum");
        assertFalse(inbound.isSameCcyShortcircuit());
        assertNotNull(inbound.getOfferRateColl(), "FX1015 field #14 must be populated");
        assertEquals(0, new BigDecimal("1.01010103").compareTo(inbound.getOfferRateColl()),
                "offerRateColl (FX1015 #14) carried verbatim from CommittedFxView");
        assertEquals(0, new BigDecimal("1316.25000000").compareTo(inbound.getCrossRate()));
        assertEquals(0, new BigDecimal("38.99").compareTo(inbound.getCollectionAmount()));
        assertEquals("USD", inbound.getCollectionCcy());
        assertEquals(0, new BigDecimal("50000").compareTo(inbound.getPayoutAmount()));
        assertEquals("KRW", inbound.getPayoutCcy());
        assertEquals(0, new BigDecimal("37.04").compareTo(inbound.getUsdAmount()));

        // OUTBOUND → FX1014 carrier.
        CommittedTransaction outbound = result.get(1);
        assertEquals(TransactionDirection.OUTBOUND, outbound.getDirection());
        assertEquals(3002L, outbound.getTxnId());
    }

    @Test
    @DisplayName("fetchCommittedFx: partnerId appended to URI when provided")
    void fetchCommittedFx_withPartnerId_appendsToUri() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions/fx-committed"
                        + "?from=2026-03-01&to=2026-03-31&partnerId=99"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                port.fetchCommittedFx(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 99L);

        mockServer.verify();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("fetchCommittedFx: record with unknown direction is skipped, not fatal")
    void fetchCommittedFx_unknownDirection_skipped() {
        mockServer.expect(requestTo(
                "http://transaction-mgmt:8080/v1/transactions/fx-committed"
                        + "?from=2026-05-20&to=2026-05-20"))
                .andRespond(withSuccess(unknownDirectionJson(), MediaType.APPLICATION_JSON));

        List<CommittedTransaction> result =
                port.fetchCommittedFx(LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 20), null);

        mockServer.verify();
        assertEquals(1, result.size(), "only the valid INBOUND record survives");
        assertEquals(TransactionDirection.INBOUND, result.get(0).getDirection());
    }

    // -------------------------------------------------------------------------
    // CommittedFxView JSON fixtures (decimal-string money/rates per MONEY_CONVENTION)
    // -------------------------------------------------------------------------

    private static String twoViewsJson() {
        return """
                [
                  {
                    "txnId": 3001,
                    "txnRef": "SCH-IN-001",
                    "partnerId": 42,
                    "direction": "INBOUND",
                    "sameCcyShortcircuit": false,
                    "offerRateColl": "1.01010103",
                    "crossRate": "1316.25000000",
                    "collectionAmount": "38.99",
                    "collectionCcy": "USD",
                    "payoutAmount": "50000",
                    "payoutCcy": "KRW",
                    "usdAmount": "37.04",
                    "collectionMarginUsd": "0.50",
                    "payoutMarginUsd": "0.10",
                    "committedAt": "2026-05-20T03:00:00Z"
                  },
                  {
                    "txnId": 3002,
                    "txnRef": "SCH-OUT-001",
                    "partnerId": 43,
                    "direction": "OUTBOUND",
                    "sameCcyShortcircuit": false,
                    "offerRateColl": "1.00500000",
                    "crossRate": "0.99502488",
                    "collectionAmount": "105.00",
                    "collectionCcy": "USD",
                    "payoutAmount": "100.00",
                    "payoutCcy": "USD",
                    "usdAmount": "104.47",
                    "collectionMarginUsd": "0.53",
                    "payoutMarginUsd": "0.00",
                    "committedAt": "2026-05-20T04:00:00Z"
                  }
                ]
                """;
    }

    private static String unknownDirectionJson() {
        return """
                [
                  {
                    "txnId": 3001,
                    "txnRef": "SCH-IN-001",
                    "partnerId": 42,
                    "direction": "INBOUND",
                    "sameCcyShortcircuit": false,
                    "offerRateColl": "1.01010103",
                    "crossRate": "1316.25000000",
                    "collectionAmount": "38.99",
                    "collectionCcy": "USD",
                    "payoutAmount": "50000",
                    "payoutCcy": "KRW",
                    "usdAmount": "37.04",
                    "collectionMarginUsd": "0.50",
                    "payoutMarginUsd": "0.10",
                    "committedAt": "2026-05-20T03:00:00Z"
                  },
                  {
                    "txnId": 9999,
                    "txnRef": "SCH-BAD-001",
                    "partnerId": 7,
                    "direction": "SIDEWAYS",
                    "sameCcyShortcircuit": false,
                    "offerRateColl": "1.0",
                    "crossRate": "1.0",
                    "collectionAmount": "1",
                    "collectionCcy": "USD",
                    "payoutAmount": "1",
                    "payoutCcy": "USD",
                    "usdAmount": "1",
                    "collectionMarginUsd": "0",
                    "payoutMarginUsd": "0",
                    "committedAt": "2026-05-20T05:00:00Z"
                  }
                ]
                """;
    }
}
