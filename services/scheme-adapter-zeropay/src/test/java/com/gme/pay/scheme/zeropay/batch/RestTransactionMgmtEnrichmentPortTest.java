package com.gme.pay.scheme.zeropay.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MockRestServiceServer} unit tests for {@link RestTransactionMgmtEnrichmentPort}.
 * No Spring context — transaction-management is mocked over HTTP (it is not running in CI/local).
 */
class RestTransactionMgmtEnrichmentPortTest {

    private static final String BASE = "http://localhost:8080";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 9);

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestTransactionMgmtEnrichmentPort port;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server  = MockRestServiceServer.bindTo(builder).build();
        port    = new RestTransactionMgmtEnrichmentPort(builder.build());
    }

    @Test
    void refundEnrichment_mapsRealAmountMerchantAndQrFromCanonicalView() {
        // Canonical transaction-mgmt (producer) JSON: txnRef / originalPaymentTxnRef /
        // refundAmountKrw / merchantId / qrCodeId / schemeTxnRef. Money rides as a String
        // (MONEY_CONVENTION). Keyed by the refund leg's scheme-side ref (schemeTxnRef).
        server.expect(requestTo(BASE + "/v1/transactions/refunded?refundedOn=2026-06-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"txnRef\":\"TXN-R1\","
                        + "\"originalPaymentTxnRef\":\"TXN-P1\","
                        + "\"schemeTxnRef\":\"ZPR1\","
                        + "\"refundAmountKrw\":\"33000\","
                        + "\"merchantId\":\"M9\","
                        + "\"qrCodeId\":\"QR-REAL\"}]",
                        MediaType.APPLICATION_JSON));

        Map<String, ZpBatchEnrichmentPort.RefundEnrichment> out = port.refundEnrichment(DATE);
        // Keyed by schemeTxnRef (matches zp_committed_txns.zeropayTxnRef of the refund leg).
        ZpBatchEnrichmentPort.RefundEnrichment e = out.get("ZPR1");
        // Regression: these were ALL null before the canonical-view fix (ad-hoc record bound
        // refundSchemeTxnRef/originalSchemeTxnRef, which the producer never emits).
        assertNotNull(e, "schemeTxnRef must key the refund enrichment");
        assertNotNull(e.refundAmountKrw(), "refundAmountKrw must now bind (was silently null)");
        assertEquals(0, e.refundAmountKrw().compareTo(new BigDecimal("33000")));
        assertEquals("M9", e.merchantId());
        assertEquals("QR-REAL", e.qrCodeId());
        server.verify();
    }

    @Test
    void refundEnrichment_fallsBackToOriginalPaymentRefWhenNoSchemeRef() {
        // When the producer row carries no schemeTxnRef, keying falls back to
        // originalPaymentTxnRef (then txnRef) so the enrichment still attaches.
        server.expect(requestTo(BASE + "/v1/transactions/refunded?refundedOn=2026-06-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"txnRef\":\"TXN-R2\","
                        + "\"originalPaymentTxnRef\":\"ZP-ORIG2\","
                        + "\"refundAmountKrw\":\"5000\","
                        + "\"merchantId\":\"M2\"}]",
                        MediaType.APPLICATION_JSON));

        Map<String, ZpBatchEnrichmentPort.RefundEnrichment> out = port.refundEnrichment(DATE);
        ZpBatchEnrichmentPort.RefundEnrichment e = out.get("ZP-ORIG2");
        assertNotNull(e);
        assertEquals(0, e.refundAmountKrw().compareTo(new BigDecimal("5000")));
        assertEquals("M2", e.merchantId());
        server.verify();
    }

    @Test
    void refundEnrichment_fallsBackToEmptyMapOnUpstreamError() {
        server.expect(requestTo(BASE + "/v1/transactions/refunded?refundedOn=2026-06-09"))
                .andRespond(withServerError());

        // Best-effort: never throws, returns empty so the batch keeps captured values.
        assertTrue(port.refundEnrichment(DATE).isEmpty());
        server.verify();
    }

    @Test
    void settlementValueDates_mapsSettlementDateByTxnRef() {
        server.expect(requestTo(BASE + "/v1/transactions/fx-committed?committedOn=2026-06-09"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"schemeTxnRef\":\"ZP1\",\"settlementDate\":\"2026-06-11\"},"
                        + "{\"schemeTxnRef\":\"ZP2\",\"settlementDate\":null}]",
                        MediaType.APPLICATION_JSON));

        Map<String, LocalDate> out = port.settlementValueDates(DATE);
        assertEquals(LocalDate.of(2026, 6, 11), out.get("ZP1"));
        // Null settlement date is skipped (caller then falls back to business date).
        assertTrue(out.get("ZP2") == null);
        server.verify();
    }

    @Test
    void settlementValueDates_fallsBackToEmptyMapOnUpstreamError() {
        server.expect(requestTo(BASE + "/v1/transactions/fx-committed?committedOn=2026-06-09"))
                .andRespond(withServerError());

        assertTrue(port.settlementValueDates(DATE).isEmpty());
        server.verify();
    }
}
