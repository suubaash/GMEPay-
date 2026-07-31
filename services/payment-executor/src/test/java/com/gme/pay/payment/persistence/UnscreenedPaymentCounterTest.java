package com.gme.pay.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.UnscreenedReason;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>T5-3</b>: the unscreened-payment count is real, durable and queryable — Flyway V010 against H2 in
 * PostgreSQL mode (no Docker).
 *
 * <p>What is being proven is the sentence a regulator asks for: <b>"how many payments went through
 * unscreened?"</b> has a number behind it. Before this table the honest answer was "unknown", which is
 * the worst of the three possible answers.
 *
 * <p>The aggregate shape is asserted deliberately: repeated payments of the same cause increment ONE row
 * rather than inserting many, distinct causes and distinct parties stay separate, and the migration's
 * CHECK constraints actually reject an off-roster value (so a future reason cannot be smuggled in as free
 * text).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UnscreenedPaymentCounterTest {

    private static final Instant T0 = Instant.parse("2026-07-28T09:15:00Z");
    private static final LocalDate D0 = LocalDate.parse("2026-07-28");

    @Autowired
    private UnscreenedPaymentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private UnscreenedPaymentCounter counter(Instant now) {
        // A fresh instance every time: the counter must hold no state of its own.
        return new UnscreenedPaymentCounter(repository, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Isolate each test from committed rows written by its siblings in this shared H2 instance. */
    private long countFor(String partner) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(payment_count), 0) FROM unscreened_payments WHERE partner_ref = ?",
                Long.class, partner);
        return n == null ? 0L : n;
    }

    @Test
    @DisplayName("repeated unscreened payments increment ONE aggregate row and are summable")
    void repeatedPayments_incrementOneRow() {
        UnscreenedPaymentCounter counter = counter(T0);
        for (int i = 0; i < 7; i++) {
            counter.countUnscreened(UnscreenedReason.NO_PROVIDER, PaymentParty.PAYER, "none",
                    "P-INCR", "PTXN-" + i);
        }

        assertThat(countFor("P-INCR")).isEqualTo(7L);

        // Read the ENTITY back too, not just a raw SUM. This is the assertion that caught a real defect:
        // a JPQL bulk UPDATE bypasses the first-level cache, so without clearAutomatically on
        // UnscreenedPaymentRepository.increment the persisted row said 7 while every JPA reader in the
        // same persistence context still saw 1 — an under-count in the one table whose entire purpose is
        // to state a number honestly.
        var row = repository
                .findByGapDateAndReasonCodeAndPartyRoleAndProviderIdAndPartnerRef(
                        D0, "NO_PROVIDER", "PAYER", "none", "P-INCR")
                .orElseThrow();
        assertThat(row.getPaymentCount()).isEqualTo(7L);
        // Evidence anchors: the first and last payment of the run, so an auditor has somewhere to start.
        assertThat(row.getFirstPaymentRef()).isEqualTo("PTXN-0");
        assertThat(row.getLastPaymentRef()).isEqualTo("PTXN-6");
        assertThat(row.getFirstSeenAt()).isEqualTo(T0);

        // One row, not seven: the point of the aggregate.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM unscreened_payments WHERE partner_ref = 'P-INCR'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("distinct causes and distinct parties are counted separately, never blurred together")
    void causesAndPartiesStaySeparate() {
        UnscreenedPaymentCounter counter = counter(T0);
        counter.countUnscreened(UnscreenedReason.NO_PROVIDER, PaymentParty.PAYER, "none", "P-SEP", "a");
        counter.countUnscreened(UnscreenedReason.NO_SUBJECT_IDENTITY, PaymentParty.PAYER, "none",
                "P-SEP", "b");
        counter.countUnscreened(UnscreenedReason.NO_PROVIDER, PaymentParty.BENEFICIARY, "none",
                "P-SEP", "c");

        // Three different findings with three different owners must not collapse into one number.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM unscreened_payments WHERE partner_ref = 'P-SEP'", Long.class))
                .isEqualTo(3L);
        assertThat(countFor("P-SEP")).isEqualTo(3L);
    }

    @Test
    @DisplayName("the count is queryable by window and by cause, and the total is a real number")
    void countsAreQueryable() {
        UnscreenedPaymentCounter counter = counter(T0);
        counter.countUnscreened(UnscreenedReason.PROVIDER_ERROR, PaymentParty.PAYER, "acme",
                "P-QRY", "x");
        counter.countUnscreened(UnscreenedReason.PROVIDER_ERROR, PaymentParty.PAYER, "acme",
                "P-QRY", "y");

        List<UnscreenedPaymentEntity> rows = counter.recent(D0, "PROVIDER_ERROR", 10);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(r -> "PROVIDER_ERROR".equals(r.getReasonCode()));

        // A window that starts after the fact excludes it — the query is a real filter, not a stub.
        assertThat(counter.recent(D0.plusDays(1), "PROVIDER_ERROR", 10))
                .noneMatch(r -> "P-QRY".equals(r.getPartnerRef()));

        assertThat(counter.total(D0)).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("reads are bounded — no caller can pull an unbounded history")
    void readsAreBounded() {
        assertThat(counter(T0).recent(null, null, 100_000))
                .hasSizeLessThanOrEqualTo(UnscreenedPaymentCounter.MAX_LIMIT);
    }

    @Test
    @DisplayName("a null partner becomes 'unknown' rather than failing or vanishing")
    void nullPartner_becomesUnknown() {
        counter(T0).countUnscreened(UnscreenedReason.NO_PROVIDER, PaymentParty.MERCHANT, "none",
                null, null);

        assertThat(repository
                .findByGapDateAndReasonCodeAndPartyRoleAndProviderIdAndPartnerRef(
                        D0, "NO_PROVIDER", "MERCHANT", "none",
                        UnscreenedPaymentEntity.UNKNOWN_PARTNER))
                .isPresent();
    }

    @Test
    @DisplayName("the counter never throws — a coverage-write failure must not fail a payment")
    void counterNeverThrows() {
        // A null reason / party is the degenerate caller error; it must be swallowed, not propagated
        // into the pay path.
        assertThatCode(() -> counter(T0).countUnscreened(null, PaymentParty.PAYER, "none", "p", "r"))
                .doesNotThrowAnyException();
        assertThatCode(() -> counter(T0).countUnscreened(UnscreenedReason.NO_PROVIDER, null, "none",
                "p", "r")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V010's CHECK constraints reject an off-roster reason or party — the roster is enforced in the DB")
    void migrationChecksRejectOffRosterValues() {
        // Belt and braces around the enum: a future code path that writes a raw string cannot invent a
        // reason (or a party) that no consumer knows how to interpret.
        assertThatCode(() -> jdbc.update("""
                INSERT INTO unscreened_payments
                    (gap_date, reason_code, party_role, provider_id, payment_count,
                     first_seen_at, last_seen_at, partner_ref)
                VALUES (?, 'SCREENED_PROBABLY', 'PAYER', 'none', 1, ?, ?, 'P-CHK')
                """, D0, T0, T0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatCode(() -> jdbc.update("""
                INSERT INTO unscreened_payments
                    (gap_date, reason_code, party_role, provider_id, payment_count,
                     first_seen_at, last_seen_at, partner_ref)
                VALUES (?, 'NO_PROVIDER', 'THE_BANK', 'none', 1, ?, ?, 'P-CHK')
                """, D0, T0, T0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the unique key makes the aggregate identity real — a duplicate row cannot be inserted")
    void uniqueKeyIsEnforced() {
        counter(T0).countUnscreened(UnscreenedReason.NO_PROVIDER, PaymentParty.PAYER, "none",
                "P-UNIQ", "r");

        assertThatCode(() -> jdbc.update("""
                INSERT INTO unscreened_payments
                    (gap_date, reason_code, party_role, provider_id, payment_count,
                     first_seen_at, last_seen_at, partner_ref)
                VALUES (?, 'NO_PROVIDER', 'PAYER', 'none', 1, ?, ?, 'P-UNIQ')
                """, D0, T0, T0))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("no counterparty PII column exists on the coverage table")
    void tableCarriesNoCounterpartyPii() {
        List<String> columns = jdbc.queryForList("""
                SELECT LOWER(column_name) FROM information_schema.columns
                WHERE LOWER(table_name) = 'unscreened_payments'
                """, String.class);

        // Measuring a compliance gap must not create a new plaintext PII store as a side effect: this
        // platform has no column encryption (T5-5). Presence/absence of an attribute is captured by
        // reason_code; the values are never stored.
        assertThat(columns).isNotEmpty();
        assertThat(columns).noneMatch(c -> c.contains("name") || c.contains("dob")
                || c.contains("birth") || c.contains("national") || c.contains("address"));
    }
}
