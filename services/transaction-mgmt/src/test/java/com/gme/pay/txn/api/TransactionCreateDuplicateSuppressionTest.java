package com.gme.pay.txn.api;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.txn.api.dto.CreateTransactionRequest;
import com.gme.pay.txn.api.dto.CreateTransactionResponse;
import com.gme.pay.txn.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>The defect:</b> {@code POST /v1/transactions} created the transaction and claimed the
 * {@code Idempotency-Key} <em>afterwards</em>, so two concurrent identical requests produced
 * <b>two rows</b> — one of them orphaned, with a {@code txn_ref} no caller ever saw.
 *
 * <p>Two independent defences are asserted here, and they are independent on purpose:
 * <ol>
 *   <li><b>The claim, taken first</b> — two real threads, one key: exactly one 201 and one 409, and
 *       exactly ONE row. Proven with threads rather than mocks, because the property is about what two
 *       simultaneous callers do to one database, which a mock cannot show.</li>
 *   <li><b>The DB unique index</b> ({@code ux_transactions_partner_txn_ref}, V015) — rejects a duplicate
 *       {@code (partner_id, partner_txn_ref)} <em>with the store not involved at all</em>. This matters
 *       because payment-executor's internal {@code createPending} sends <b>no</b> Idempotency-Key, so on
 *       that path the index is the only duplicate defence there is.</li>
 * </ol>
 *
 * <p>Runs against H2 in PostgreSQL mode with the real Flyway set; no Docker.
 * {@code @Transactional(NOT_SUPPORTED)} because the whole point is what COMMITS — a rolled-back test
 * transaction would hide both properties, and the second thread could not see into it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionCreateDuplicateSuppressionTest {

    @Autowired
    private TransactionController controller;

    @Autowired
    private TransactionService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_keys");
        jdbc.update("DELETE FROM transactions");
    }

    private static CreateTransactionRequest request(String partnerTxnRef) {
        return new CreateTransactionRequest(
                42L, partnerTxnRef, "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000.00000000"), "KRW",
                new BigDecimal("100.00000000"), "USD",
                "MERCH-001", "QUOTE-001", null,
                null, null, null, null, null, null, null, null,
                null, null);
    }

    private int transactionCount(String partnerTxnRef) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE partner_txn_ref = ?", Integer.class,
                partnerTxnRef);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ 1. the claim, taken first

    @Test
    @DisplayName("two CONCURRENT identical creates: exactly one 201, one 409, and ONE row")
    void concurrentDuplicatesCreateExactlyOneTransaction() throws Exception {
        String key = "IDEMPOTENT-KEY-CONCURRENT-1";
        String ref = "PE-REF-CONCURRENT-1";
        CountDownLatch go = new CountDownLatch(1);

        Callable<Object> attempt = () -> {
            go.await();
            try {
                return controller.create(key, request(ref));
            } catch (RuntimeException e) {
                return e;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results = new ArrayList<>();
        try {
            Future<Object> f1 = pool.submit(attempt);
            Future<Object> f2 = pool.submit(attempt);
            go.countDown();
            results.add(f1.get(30, TimeUnit.SECONDS));
            results.add(f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<Object> created = results.stream()
                .filter(r -> r instanceof ResponseEntity)
                .toList();
        List<ApiException> conflicts = results.stream()
                .filter(r -> r instanceof ApiException)
                .map(r -> (ApiException) r)
                .toList();

        assertThat(created).as("exactly one caller created").hasSize(1);
        assertThat(((ResponseEntity<?>) created.get(0)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(conflicts).as("the other got a 409, not a second transaction").hasSize(1);
        assertThat(conflicts.get(0).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(conflicts.get(0).errorCode().httpStatus()).isEqualTo(409);

        assertThat(transactionCount(ref))
                .as("THE POINT: before the claim moved first, this was 2 — and one of them was an "
                        + "orphaned money row nobody was ever told about")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the 409 is not a lost payment: retrying the same key REPLAYS the winner's response")
    void conflictIsRetryableAndReplays() {
        String key = "IDEMPOTENT-KEY-REPLAY-1";
        String ref = "PE-REF-REPLAY-1";

        ResponseEntity<CreateTransactionResponse> first = controller.create(key, request(ref));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<CreateTransactionResponse> retry = controller.create(key, request(ref));

        assertThat(retry.getStatusCode())
                .as("a completed key replays with 200, not 201")
                .isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().txnRef())
                .as("byte-for-byte the first response — the same transaction, not a new one")
                .isEqualTo(first.getBody().txnRef());
        assertThat(transactionCount(ref)).isEqualTo(1);
    }

    @Test
    @DisplayName("a FAILED create releases the claim, so the retry is not 409'd for two minutes")
    void failedCreateReleasesTheClaim() {
        String key = "IDEMPOTENT-KEY-FAILED-1";
        // collectionAmount <= 0 is rejected by the service, after the claim was taken.
        CreateTransactionRequest invalid = new CreateTransactionRequest(
                42L, "PE-REF-FAILED-1", "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000.00000000"), "KRW",
                BigDecimal.ZERO, "USD", "MERCH-001", "QUOTE-001", null,
                null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> controller.create(key, invalid))
                .isInstanceOf(ApiException.class);

        // Same key, now with a valid body: must be allowed to create.
        ResponseEntity<CreateTransactionResponse> retry =
                controller.create(key, request("PE-REF-FAILED-1"));

        assertThat(retry.getStatusCode())
                .as("holding the claim after a failure would turn one bad request into two minutes of "
                        + "409s on a key the client is entitled to reuse")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(transactionCount("PE-REF-FAILED-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("no Idempotency-Key: unchanged behaviour (201), and the store is untouched")
    void noKeyIsUnchanged() {
        ResponseEntity<CreateTransactionResponse> response =
                controller.create(null, request("PE-REF-NOKEY-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class))
                .isZero();
    }

    // ------------------------------------------------ 2. the DB index, independent of the store

    @Test
    @DisplayName("the DB rejects a duplicate (partner_id, partner_txn_ref) with NO store involved")
    void uniqueIndexRejectsDuplicateIndependentlyOfTheStore() {
        // Straight through the service: this is payment-executor's path, which sends no
        // Idempotency-Key at all, so nothing consults idempotency_keys here.
        service.createFromPaymentExecutor(42L, "PE-REF-UNIQUE-1", "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000"), "KRW", new BigDecimal("100"), "USD",
                "MERCH-001", "QUOTE-001", null);

        assertThatThrownBy(() -> service.createFromPaymentExecutor(
                42L, "PE-REF-UNIQUE-1", "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000"), "KRW", new BigDecimal("100"), "USD",
                "MERCH-001", "QUOTE-001", null))
                .as("IdempotencyStore's javadoc promised a DB backstop for years while none existed; "
                        + "V015 is that backstop, and this is the proof it bites")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(transactionCount("PE-REF-UNIQUE-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class))
                .as("no idempotency row was written — the two defences really are independent")
                .isZero();
    }

    @Test
    @DisplayName("the same partner_txn_ref under a DIFFERENT partner is allowed")
    void theIndexIsScopedToThePartner() {
        service.createFromPaymentExecutor(42L, "PE-REF-SHARED", "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000"), "KRW", new BigDecimal("100"), "USD", "M", "Q", null);
        service.createFromPaymentExecutor(43L, "PE-REF-SHARED", "zeropay_kr", "INBOUND", "QR",
                new BigDecimal("130000"), "KRW", new BigDecimal("100"), "USD", "M", "Q", null);

        assertThat(transactionCount("PE-REF-SHARED"))
                .as("a reference is only unique WITHIN a partner; two partners may both call theirs "
                        + "'INV-1'")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("legacy rows (NULL partner_id + partner_txn_ref) are NOT constrained by the index")
    void legacyRowsAreUnaffected() {
        service.create("LEGACY-PARTNER", new BigDecimal("100"), "USD",
                new BigDecimal("130000"), "KRW");
        service.create("LEGACY-PARTNER", new BigDecimal("100"), "USD",
                new BigDecimal("130000"), "KRW");

        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE partner_txn_ref IS NULL", Integer.class);
        assertThat(nulls)
                .as("both PostgreSQL and H2 treat NULLs as distinct in a unique index, so the legacy "
                        + "5-field create path is untouched by V015")
                .isEqualTo(2);
    }
}
