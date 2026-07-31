package com.gme.pay.scheme.sendmn.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local unit slice: Flyway V001 + JPA mappings + key constraints against the in-memory
 * H2 (PostgreSQL-mode) datasource from {@code application.properties} — same convention
 * as scheme-adapter-zeropay's H2 slices. No Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SmnPersistenceH2SliceTest {

    @Autowired
    private SmnPaymentRepository payments;

    @Autowired
    private SmnFxRateRepository fxRates;

    @Test
    @DisplayName("smn_payments: round-trips a payment and enforces the unique TX_TOKEN_NO")
    void payment_roundTripAndUniqueToken() {
        SmnPaymentEntity p = new SmnPaymentEntity(
                "SMN20260727041530ABC234", "qr-payload", "merchant-guid", "UB Store",
                new BigDecimal("10000.00"));
        p.recordFxUsed("TICKER-1", new BigDecimal("3373.000000"), "USD", new BigDecimal("2.9647"));
        p.setStatus(SmnPaymentEntity.Status.APPROVED);
        p.setPaymentNo("PN-1");
        p.setPaymentReceiptNo("GME1453767113");
        payments.saveAndFlush(p);

        SmnPaymentEntity loaded = payments.findByTxTokenNo("SMN20260727041530ABC234").orElseThrow();
        assertNotNull(loaded.getId());
        assertNotNull(loaded.getCreatedAt());
        assertEquals(SmnPaymentEntity.Status.APPROVED, loaded.getStatus());
        assertEquals(0, loaded.getLocalAmount().compareTo(new BigDecimal("10000.00")));
        assertEquals(0, loaded.getSettlementAmount().compareTo(new BigDecimal("2.9647")));
        assertEquals("TICKER-1", loaded.getFxTickerNo());

        // TX_TOKEN_NO is the scheme idempotency key — the DB must reject a second row.
        assertThrows(DataIntegrityViolationException.class, () -> payments.saveAndFlush(
                new SmnPaymentEntity("SMN20260727041530ABC234", "other-qr", null, null, null)));
    }

    @Test
    @DisplayName("smn_payments: V002 hub_reference round-trips; by-reference lookup is newest-first")
    void payment_hubReferenceLookup() {
        SmnPaymentEntity first = new SmnPaymentEntity(
                "SMN-REF-A1", "qr-1", "merchant-guid", "UB Store", new BigDecimal("100.00"));
        first.setHubReference("ref-42");
        payments.saveAndFlush(first);
        // A hub retry after a lost verify-qr response mints a second attempt row for the
        // SAME reference (new TX_TOKEN_NO) — the column is deliberately NOT unique.
        SmnPaymentEntity second = new SmnPaymentEntity(
                "SMN-REF-A2", "qr-1", "merchant-guid", "UB Store", new BigDecimal("100.00"));
        second.setHubReference("ref-42");
        payments.saveAndFlush(second);

        List<SmnPaymentEntity> attempts = payments.findByHubReferenceOrderByIdDesc("ref-42");
        assertEquals(2, attempts.size());
        assertEquals("SMN-REF-A2", attempts.get(0).getTxTokenNo(), "newest attempt first");
        assertEquals("ref-42", attempts.get(0).getHubReference());

        assertTrue(payments.findByHubReferenceOrderByIdDesc("ref-unknown").isEmpty());
    }

    @Test
    @DisplayName("smn_fx_rates: unique FX_TICKER_NO + latest-rate lookup picks newest notice date")
    void fxRates_uniqueTickerAndLatestLookup() {
        fxRates.saveAndFlush(new SmnFxRateEntity(
                "TICKER-OLD", "20260726", new BigDecimal("3370.000000"), "MNT", "USD"));
        fxRates.saveAndFlush(new SmnFxRateEntity(
                "TICKER-NEW", "20260727", new BigDecimal("3373.000000"), "MNT", "USD"));

        SmnFxRateEntity latest = fxRates
                .findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc("MNT", "USD")
                .orElseThrow();
        assertEquals("TICKER-NEW", latest.getFxTickerNo());
        assertEquals(0, latest.getRate().compareTo(new BigDecimal("3373")));

        assertTrue(fxRates.findByFxTickerNo("TICKER-OLD").isPresent());
        assertThrows(DataIntegrityViolationException.class, () -> fxRates.saveAndFlush(
                new SmnFxRateEntity("TICKER-NEW", "20260728", new BigDecimal("3380.000000"), "MNT", "USD")));
    }

    @Test
    @DisplayName("smn_fx_rates: unregistered pair → empty (adapter refuses to Confirm without a rate)")
    void fxRates_missingPairIsEmpty() {
        assertTrue(fxRates
                .findFirstByLocalCurCodeAndSettlementCurCodeOrderByNoticeDateDescIdDesc("KHR", "USD")
                .isEmpty());
    }
}
