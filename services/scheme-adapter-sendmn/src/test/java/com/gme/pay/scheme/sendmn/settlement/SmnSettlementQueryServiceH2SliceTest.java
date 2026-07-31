package com.gme.pay.scheme.sendmn.settlement;

import com.gme.pay.scheme.sendmn.dto.DailySettlementResponse;
import com.gme.pay.scheme.sendmn.fx.FxRateService;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnFxRateRepository;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentEntity;
import com.gme.pay.scheme.sendmn.persistence.SmnPaymentRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 (PostgreSQL-mode) slice for the read-only daily settlement query that feeds
 * settlement-reconciliation's SENDMN three-way tie-out: only APPROVED rows, only rows inside the
 * KST business day, each carrying the registered rate + USD settlement amount its Confirm used.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SmnSettlementQueryServiceH2SliceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    @Autowired
    private SmnPaymentRepository payments;

    @Autowired
    private SmnFxRateRepository fxRateRepository;

    @Autowired
    private EntityManager em;

    private SmnSettlementQueryService service;

    @BeforeEach
    void setUp() {
        service = new SmnSettlementQueryService(payments, new FxRateService(fxRateRepository), "Asia/Seoul");
    }

    @Test
    @DisplayName("returns only APPROVED rows inside the KST business day, with rate + USD settlement amount")
    void returnsApprovedRowsInsideTheKstDay() {
        // Inside the day: 10:00 KST
        save("SMN-TOK-1", "SENDMN-ref-1", new BigDecimal("10000.00"),
                new BigDecimal("3373.000000"), new BigDecimal("2.9647"),
                SmnPaymentEntity.Status.APPROVED, DATE.atTime(10, 0).atZone(KST).toInstant());
        // Inside the day but not confirmed → excluded
        save("SMN-TOK-2", "SENDMN-ref-2", new BigDecimal("5000.00"),
                new BigDecimal("3373.000000"), new BigDecimal("1.4824"),
                SmnPaymentEntity.Status.PENDING, DATE.atTime(11, 0).atZone(KST).toInstant());
        // Next KST day (00:30 KST on the 29th) → excluded
        save("SMN-TOK-3", "SENDMN-ref-3", new BigDecimal("7000.00"),
                new BigDecimal("3373.000000"), new BigDecimal("2.0753"),
                SmnPaymentEntity.Status.APPROVED, DATE.plusDays(1).atTime(0, 30).atZone(KST).toInstant());

        fxRateRepository.saveAndFlush(new SmnFxRateEntity(
                "TICKER-LATEST", "20260728", new BigDecimal("3373.000000"), "MNT", "USD"));
        em.clear();

        DailySettlementResponse response = service.confirmedOn(DATE);

        assertThat(response.date()).isEqualTo("2026-07-28");
        assertThat(response.localCurCode()).isEqualTo("MNT");
        assertThat(response.settlementCurCode()).isEqualTo("USD");
        assertThat(response.latestRegisteredRate()).isEqualByComparingTo("3373.000000");
        assertThat(response.count()).isEqualTo(1);

        DailySettlementResponse.Row row = response.rows().get(0);
        assertThat(row.hubReference()).isEqualTo("SENDMN-ref-1");
        assertThat(row.txTokenNo()).isEqualTo("SMN-TOK-1");
        assertThat(row.localAmount()).isEqualByComparingTo("10000.00");
        assertThat(row.fxUsdBuyRate()).isEqualByComparingTo("3373.000000");
        assertThat(row.settlementAmount()).isEqualByComparingTo("2.9647");
        assertThat(row.status()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("a day with no confirmed payments yields an empty row list, not an error")
    void emptyDay() {
        DailySettlementResponse response = service.confirmedOn(DATE);

        assertThat(response.count()).isZero();
        assertThat(response.rows()).isEmpty();
        assertThat(response.latestRegisteredRate()).isNull();
    }

    /**
     * Persists an attempt and then forces {@code created_at} to the business instant under test —
     * the column is stamped by {@code @PrePersist} and has no setter, so the window is exercised
     * with a native update rather than by weakening the entity.
     */
    private void save(String token, String hubReference, BigDecimal mnt, BigDecimal rate,
                      BigDecimal usd, SmnPaymentEntity.Status status, Instant createdAt) {
        SmnPaymentEntity p = new SmnPaymentEntity(token, "qr-payload", "merchant-guid", "UB Store", mnt);
        p.setHubReference(hubReference);
        p.recordFxUsed("TICKER-1", rate, "USD", usd);
        p.setStatus(status);
        payments.saveAndFlush(p);

        em.createNativeQuery("UPDATE smn_payments SET created_at = :ts WHERE tx_token_no = :tok")
                .setParameter("ts", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .setParameter("tok", token)
                .executeUpdate();
        em.flush();
        em.clear();
    }
}
