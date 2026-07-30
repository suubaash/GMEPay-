package com.gme.pay.settlement.web;

import com.gme.pay.settlement.batch.SettlementBatchStatus;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineEntity;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.transmission.SettlementTransmissionChannelRegistry;
import com.gme.pay.settlement.transmission.SettlementTransmissionState;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * GAP T4-5: the persisted-batch read surface, against real H2 migrated by Flyway (so V013 —
 * {@code transmission_state} plus the {@code transmitted_at} CHECK constraint — is genuinely exercised
 * rather than asserted about).
 *
 * <p>Fixture (business dates 2026-06-13..15, one merchant of interest and one not):
 * <pre>
 *   ZP0061-20260613-MORNING   RECONCILED  MRC-A +50000 (matched), MRC-B +11000
 *   ZP0061-20260614-MORNING   RECEIVED    MRC-A +34720 (matched), MRC-A -4720 (clawback, unmatched)
 *   ZP0061-20260615-MORNING   GENERATED   MRC-B +9000
 * </pre>
 * MRC-A's statement over 06-13..06-15 is therefore net 80000 = 84720 paid − 4720 clawed back, over two
 * batches, with one open line — and <b>zero</b> transmitted entries.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class SettlementBatchQueryServiceIT {

    private static final String B13 = "ZP0061-20260613-MORNING";
    private static final String B14 = "ZP0061-20260614-MORNING";
    private static final String B15 = "ZP0061-20260615-MORNING";

    private static final LocalDate D13 = LocalDate.of(2026, 6, 13);
    private static final LocalDate D14 = LocalDate.of(2026, 6, 14);
    private static final LocalDate D15 = LocalDate.of(2026, 6, 15);

    @Autowired private SettlementBatchRepository batchRepository;
    @Autowired private SettlementLineRepository lineRepository;
    @Autowired private EntityManager em;

    private SettlementBatchQueryService service;

    @BeforeEach
    void seed() {
        service = new SettlementBatchQueryService(batchRepository, lineRepository,
                SettlementTransmissionChannelRegistry.noChannelConfigured());

        batch(B13, D13, SettlementBatchStatus.RECONCILED, new BigDecimal("61000"));
        batch(B14, D14, SettlementBatchStatus.RECEIVED, new BigDecimal("30000"));
        batch(B15, D15, SettlementBatchStatus.GENERATED, new BigDecimal("9000"));

        line(B13, "TXN-A1", "MRC-A", new BigDecimal("50000"), true);
        line(B13, "TXN-B1", "MRC-B", new BigDecimal("11000"), true);
        line(B14, "TXN-A2", "MRC-A", new BigDecimal("34720"), true);
        line(B14, "TXN-A2-R", "MRC-A", new BigDecimal("-4720"), false);
        line(B15, "TXN-B2", "MRC-B", new BigDecimal("9000"), false);
        em.flush();
    }

    private void batch(String id, LocalDate date, SettlementBatchStatus status, BigDecimal net) {
        SettlementBatchEntity b = new SettlementBatchEntity(
                id, "ZEROPAY", date, status.name(), net, "KRW", Instant.parse("2026-06-15T20:00:00Z"));
        b.setFileType("ZP0061");
        b.setDirection("GME_TO_ZP");
        b.setSettlementWindow("MORNING");
        b.setSettlementType("N");
        b.setSettleCurrency("KRW");
        b.setNetSettlementAmount(net);
        b.setMerchantFeeTotal(new BigDecimal("424"));
        b.setRoundingResidual(new BigDecimal("0.40000000"));
        b.setRecordCount(1);
        b.setFileChecksum("cafe");
        b.markNotTransmitted(SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE,
                "No settlement transmission channel: endpoint is not set.");
        batchRepository.save(b);
    }

    private void line(String batchId, String txnRef, String merchantId, BigDecimal amount, boolean matched) {
        SettlementLineEntity l = new SettlementLineEntity(batchId, txnRef, amount, "KRW", matched);
        l.setMerchantId(merchantId);
        l.setSettlementType("N");
        l.setBookedSettlementAmount(amount);
        lineRepository.save(l);
    }

    // ---- per-batch detail -----------------------------------------------------------------

    @Test
    @DisplayName("per-batch detail reads the PERSISTED batch and its lines, with the REAL status")
    void detailReadsPersistedRows() {
        SettlementBatchDetailResponse detail = service.detail(B14).orElseThrow();

        assertThat(detail.batch().batchId()).isEqualTo(B14);
        assertThat(detail.batch().status()).isEqualTo("RECEIVED");   // not "COMPLETED"
        assertThat(detail.batch().counterpartyId()).isEqualTo("ZEROPAY");
        assertThat(detail.batch().businessDate()).isEqualTo(D14);
        assertThat(detail.batch().fileChecksum()).isEqualTo("cafe");
        assertThat(detail.lines()).extracting(SettlementBatchLineResponse::txnRef)
                .containsExactly("TXN-A2", "TXN-A2-R");
        assertThat(detail.matchedCount()).isEqualTo(1);
        assertThat(detail.openCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown batch id is empty (-> 404) — not 'the endpoint does not exist'")
    void detailOfUnknownBatch() {
        assertThat(service.detail("ZP0061-19990101-MORNING")).isEqualTo(Optional.empty());
        assertThat(service.detail(null)).isEmpty();
    }

    // ---- date-ranged list ----------------------------------------------------------------

    @Test
    @DisplayName("date-ranged list returns persisted batches, newest first, with real statuses")
    void dateRangedList() {
        List<SettlementBatchSummaryResponse> rows =
                service.batches(null, new SettlementBatchQueryService.Window(D13, D15), null, 0);

        assertThat(rows).extracting(SettlementBatchSummaryResponse::batchId)
                .containsExactly(B15, B14, B13);
        assertThat(rows).extracting(SettlementBatchSummaryResponse::status)
                .containsExactly("GENERATED", "RECEIVED", "RECONCILED");
    }

    @Test
    @DisplayName("the range is a real range: a narrower window excludes the batches outside it")
    void windowIsHonoured() {
        List<SettlementBatchSummaryResponse> rows =
                service.batches(null, new SettlementBatchQueryService.Window(D14, D14), null, 0);
        assertThat(rows).extracting(SettlementBatchSummaryResponse::batchId).containsExactly(B14);
    }

    @Test
    @DisplayName("status + counterparty filters and the row limit apply")
    void filters() {
        assertThat(service.batches("ZEROPAY", new SettlementBatchQueryService.Window(D13, D15),
                "reconciled", 0))
                .extracting(SettlementBatchSummaryResponse::batchId).containsExactly(B13);
        assertThat(service.batches("SOMEONE-ELSE",
                new SettlementBatchQueryService.Window(D13, D15), null, 0)).isEmpty();
        assertThat(service.batches(null, new SettlementBatchQueryService.Window(D13, D15), null, 2))
                .hasSize(2);
    }

    @Test
    @DisplayName("window resolution: defaults, anchoring, inversion and the hard cap")
    void windowResolution() {
        LocalDate today = LocalDate.of(2026, 6, 30);

        assertThat(SettlementBatchQueryService.resolveWindow(null, null, today))
                .isEqualTo(new SettlementBatchQueryService.Window(LocalDate.of(2026, 6, 1), today));
        assertThat(SettlementBatchQueryService.resolveWindow(D13, null, today).to())
                .isEqualTo(D13.plusDays(29));
        assertThat(SettlementBatchQueryService.resolveWindow(null, D15, today).from())
                .isEqualTo(D15.minusDays(29));
        assertThatThrownBy(() -> SettlementBatchQueryService.resolveWindow(D15, D13, today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is after");
        assertThatThrownBy(() -> SettlementBatchQueryService.resolveWindow(
                today.minusYears(5), today, today))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximum");
    }

    // ---- partner statement ---------------------------------------------------------------

    @Test
    @DisplayName("the statement is scoped by MERCHANT (settlement_lines), not by the batch counterparty")
    void statementIsMerchantScoped() {
        PartnerSettlementStatementResponse a = service.statement(
                "MRC-A", new SettlementBatchQueryService.Window(D13, D15), true);

        assertThat(a.merchantId()).isEqualTo("MRC-A");
        assertThat(a.entries()).extracting(e -> e.batch().batchId()).containsExactly(B14, B13);
        assertThat(a.currency()).isEqualTo("KRW");
        // 50000 + 34720 - 4720 = 80000
        assertThat(a.netSettlementAmount()).isEqualByComparingTo("80000");
        assertThat(a.paymentAmount()).isEqualByComparingTo("84720");
        assertThat(a.clawbackAmount()).isEqualByComparingTo("4720");
        assertThat(a.lineCount()).isEqualTo(3);
        assertThat(a.openLineCount()).isEqualTo(1);
        // MRC-B's 11000/9000 must not appear anywhere in MRC-A's statement.
        assertThat(a.entries()).allSatisfy(e ->
                assertThat(e.lines()).allSatisfy(l ->
                        assertThat(l.merchantId()).isEqualTo("MRC-A")));

        PartnerSettlementStatementResponse b = service.statement(
                "MRC-B", new SettlementBatchQueryService.Window(D13, D15), true);
        assertThat(b.netSettlementAmount()).isEqualByComparingTo("20000");
        assertThat(b.entries()).extracting(e -> e.batch().batchId()).containsExactly(B15, B13);
    }

    @Test
    @DisplayName("per-entry figures are the MERCHANT's own net, not the whole batch's net")
    void perEntryNetIsMerchantScoped() {
        PartnerSettlementStatementResponse a = service.statement(
                "MRC-A", new SettlementBatchQueryService.Window(D13, D13), true);

        // Batch B13's own net_settlement_amount is 61000 (both merchants); MRC-A's share is 50000.
        assertThat(a.entries()).hasSize(1);
        assertThat(a.entries().get(0).batch().netSettlementAmount()).isEqualByComparingTo("61000");
        assertThat(a.entries().get(0).netSettlementAmount()).isEqualByComparingTo("50000");
    }

    @Test
    @DisplayName("includeLines=false gives a summary-only statement with the same totals")
    void summaryOnlyStatement() {
        PartnerSettlementStatementResponse a = service.statement(
                "MRC-A", new SettlementBatchQueryService.Window(D13, D15), false);

        assertThat(a.netSettlementAmount()).isEqualByComparingTo("80000");
        assertThat(a.lineCount()).isEqualTo(3);
        assertThat(a.entries()).allSatisfy(e -> assertThat(e.lines()).isEmpty());
    }

    @Test
    @DisplayName("a merchant with nothing in the window gets an empty statement, not an error")
    void emptyStatement() {
        PartnerSettlementStatementResponse none = service.statement(
                "MRC-NOBODY", new SettlementBatchQueryService.Window(D13, D15), true);

        assertThat(none.entries()).isEmpty();
        assertThat(none.netSettlementAmount()).isEqualByComparingTo("0");
        assertThat(none.currency()).isNull();
        assertThat(none.transmissionChannel().live()).isFalse();
    }

    @Test
    @DisplayName("a statement needs a subject — merchantId is never defaulted to 'everyone'")
    void statementRequiresMerchant() {
        assertThatThrownBy(() -> service.statement(
                " ", new SettlementBatchQueryService.Window(D13, D15), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchantId is required");
    }

    // ---- T4-5 core: never-transmitted cannot read as transmitted -------------------------

    @Test
    @DisplayName("a RECONCILED batch that was never sent reports NOT_TRANSMITTED_CHANNEL_UNAVAILABLE")
    void reconciledIsNotTransmitted() {
        SettlementBatchSummaryResponse reconciled = service.detail(B13).orElseThrow().batch();

        assertThat(reconciled.status()).isEqualTo("RECONCILED");
        assertThat(reconciled.transmissionState())
                .isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE);
        assertThat(reconciled.transmissionState().isSent()).isFalse();
        assertThat(reconciled.transmittedAt()).isNull();
        assertThat(reconciled.transmissionChannel()).isNull();
        assertThat(reconciled.transmissionDetail()).contains("No settlement transmission channel");
    }

    @Test
    @DisplayName("no batch anywhere in a statement or list can present as transmitted")
    void nothingPresentsAsTransmitted() {
        PartnerSettlementStatementResponse a = service.statement(
                "MRC-A", new SettlementBatchQueryService.Window(D13, D15), true);
        assertThat(a.transmittedEntryCount()).isZero();
        assertThat(a.transmissionChannel().live()).isFalse();
        assertThat(a.transmissionChannel().reachableState())
                .isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE);
        assertThat(a.entries()).allSatisfy(e -> {
            assertThat(e.batch().transmissionState().isSent()).isFalse();
            assertThat(e.batch().transmittedAt()).isNull();
        });

        assertThat(service.batches(null, new SettlementBatchQueryService.Window(D13, D15), null, 0))
                .allSatisfy(r -> {
                    assertThat(r.transmissionState().isSent()).isFalse();
                    assertThat(r.transmittedAt()).isNull();
                    assertThat(r.transmissionDetail()).isNotBlank();
                });
    }

    @Test
    @DisplayName("V013 CHECK: the DB itself rejects a send timestamp without TRANSMITTED")
    void databaseRejectsOrphanTransmittedAt() {
        assertThatThrownBy(() -> {
            em.createNativeQuery("UPDATE settlement_batches SET transmitted_at = CURRENT_TIMESTAMP "
                            + "WHERE batch_id = :id")
                    .setParameter("id", B13)
                    .executeUpdate();
            em.flush();
        }).as("a hand-written UPDATE must not be able to make a never-sent batch look sent")
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining("Check constraint violation")
                .hasMessageContaining("ck_settlement_batches_transmitted_at");
    }

    @Test
    @DisplayName("an unrecognised persisted transmission state reads as NOT_TRANSMITTED, and says so")
    void unrecognisedStateNeverMeansSent() {
        // transmission_detail nulled too, so the row genuinely carries no reason of its own and the
        // read path has to explain the unrecognised value rather than echoing a stored excuse.
        em.createNativeQuery("UPDATE settlement_batches SET transmission_state = 'SENT_PROBABLY', "
                        + "transmission_detail = NULL WHERE batch_id = :id")
                .setParameter("id", B15)
                .executeUpdate();
        em.flush();
        em.clear();

        SettlementBatchSummaryResponse row = service.detail(B15).orElseThrow().batch();
        assertThat(row.transmissionState()).isEqualTo(SettlementTransmissionState.NOT_TRANSMITTED);
        assertThat(row.transmissionDetail()).contains("SENT_PROBABLY");
    }
}
