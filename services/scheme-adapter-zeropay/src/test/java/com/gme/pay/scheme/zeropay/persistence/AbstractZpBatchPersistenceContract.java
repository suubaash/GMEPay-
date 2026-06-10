package com.gme.pay.scheme.zeropay.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence contract for the batch file registry ({@code zp_batch_files}) and the
 * ZP0011/ZP0012 record staging table ({@code zp_staged_records}): Flyway migrations
 * + JPA mappings + repository round-trips + key constraints.
 *
 * <p>Runs twice: against H2 in PostgreSQL mode (local unit slice,
 * {@code ZpBatchPersistenceH2SliceTest}) and against a real postgres:16
 * Testcontainer ({@code ZpBatchPersistencePostgresIT}, {@code @Tag("docker")},
 * CI only — this machine has no Docker).</p>
 *
 * <p>{@code @DataJpaTest} lives on this base class (not the subclasses) so its
 * {@code @Transactional} semantics apply to the test methods declared here —
 * each test runs in a rolled-back transaction.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class AbstractZpBatchPersistenceContract {

    // 02:00–03:00 KST 2026-06-10 transmission window for business date 2026-06-09.
    static final Instant WINDOW_OPENS  = Instant.parse("2026-06-09T17:00:00Z");
    static final Instant WINDOW_CLOSES = Instant.parse("2026-06-09T18:00:00Z");
    static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 6, 9);
    static final String SHA256_A = "0123456789abcdef".repeat(4);
    static final String SHA256_B = "fedcba9876543210".repeat(4);
    static final String ZP_TXN_REF = "ZP20260609000001";

    @Autowired
    protected ZpBatchFileRepository batchFileRepository;

    @Autowired
    protected ZpStagedRecordRepository stagedRecordRepository;

    @Autowired
    protected TestEntityManager entityManager;

    private ZpBatchFileEntity newOutboundZp0011File() {
        return ZpBatchFileEntity.outbound(
                "ZP0011", BUSINESS_DATE, 1, "ZP0011_20260609_001.dat",
                SHA256_A, 3 * 133L, 3, new BigDecimal("40500"),
                WINDOW_OPENS, WINDOW_CLOSES);
    }

    private ZpBatchFileEntity newInboundZp0012File() {
        return ZpBatchFileEntity.inbound(
                "ZP0012", BUSINESS_DATE, 1, "ZP0012_20260609_001.dat",
                SHA256_B, 2_048L, 3, new BigDecimal("40500"),
                Instant.parse("2026-06-09T20:00:00Z"), Instant.parse("2026-06-09T21:00:00Z"),
                Instant.parse("2026-06-09T20:04:11Z"));
    }

    @Test
    @DisplayName("batch file registry round-trips checksum, window timestamps and KRW control sum")
    void batchFileRegistryRoundTrip() {
        ZpBatchFileEntity outbound = newOutboundZp0011File();
        assertEquals(ZpBatchFileEntity.STATUS_GENERATED, outbound.getStatus());
        outbound.markTransmitted(Instant.parse("2026-06-09T17:05:30Z"));

        Long id = batchFileRepository.saveAndFlush(outbound).getId();
        entityManager.clear();

        ZpBatchFileEntity loaded = batchFileRepository.findById(id).orElseThrow();
        assertEquals("ZP0011", loaded.getFileType());
        assertEquals(ZpBatchFileEntity.DIRECTION_OUTBOUND, loaded.getDirection());
        assertEquals(BUSINESS_DATE, loaded.getBusinessDate());
        assertEquals(1, loaded.getSequenceNo());
        assertEquals("ZP0011_20260609_001.dat", loaded.getFileName());
        assertEquals(SHA256_A, loaded.getSha256Checksum());
        assertEquals(3 * 133L, loaded.getFileSizeBytes());
        assertEquals(3, loaded.getRecordCount());
        // Money: BigDecimal, value-compared (scale-insensitive).
        assertEquals(0, loaded.getControlSumKrw().compareTo(new BigDecimal("40500")));
        assertEquals(ZpBatchFileEntity.STATUS_TRANSMITTED, loaded.getStatus());
        assertEquals(WINDOW_OPENS, loaded.getWindowOpensAt());
        assertEquals(WINDOW_CLOSES, loaded.getWindowClosesAt());
        assertEquals(Instant.parse("2026-06-09T17:05:30Z"), loaded.getSentAt());
        assertNull(loaded.getReceivedAt(), "outbound file has no received_at");
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());

        // Natural-key finder used by the duplicate-generation guard.
        assertTrue(batchFileRepository
                .findByFileTypeAndBusinessDateAndSequenceNo("ZP0011", BUSINESS_DATE, 1)
                .isPresent());
        assertTrue(batchFileRepository
                .findByFileTypeAndBusinessDateAndSequenceNo("ZP0011", BUSINESS_DATE, 2)
                .isEmpty());
    }

    @Test
    @DisplayName("inbound file registry round-trips received_at and direction finder")
    void inboundFileRegistryRoundTrip() {
        Long id = batchFileRepository.saveAndFlush(newInboundZp0012File()).getId();
        entityManager.clear();

        ZpBatchFileEntity loaded = batchFileRepository.findById(id).orElseThrow();
        assertEquals(ZpBatchFileEntity.DIRECTION_INBOUND, loaded.getDirection());
        assertEquals(ZpBatchFileEntity.STATUS_RECEIVED, loaded.getStatus());
        assertEquals(Instant.parse("2026-06-09T20:04:11Z"), loaded.getReceivedAt());
        assertNull(loaded.getSentAt(), "inbound file has no sent_at");

        List<ZpBatchFileEntity> inboundToday = batchFileRepository
                .findByBusinessDateAndDirection(BUSINESS_DATE, ZpBatchFileEntity.DIRECTION_INBOUND);
        assertEquals(1, inboundToday.size());
        assertEquals(SHA256_B, inboundToday.get(0).getSha256Checksum());
    }

    @Test
    @DisplayName("staged ZP0011/ZP0012 records link to their file and round-trip via the match key")
    void stagedRecordsRoundTripAndMatchKey() {
        Long outboundId = batchFileRepository.save(newOutboundZp0011File()).getId();
        Long inboundId = batchFileRepository.save(newInboundZp0012File()).getId();

        // Insert line 2 before line 1 to prove the OrderByLineNumberAsc contract.
        stagedRecordRepository.save(ZpStagedRecordEntity.zp0011Detail(
                outboundId, 2, "GME0000000000000002", "ZP20260609000002",
                "M0000002", "QR0000000000000002", BUSINESS_DATE, LocalTime.of(22, 15, 0),
                new BigDecimal("27000"), new BigDecimal("216"), new BigDecimal("54"),
                "I", "APPR00000002", "A"));
        stagedRecordRepository.save(ZpStagedRecordEntity.zp0011Detail(
                outboundId, 1, "GME0000000000000001", ZP_TXN_REF,
                "M0000001", "QR0000000000000001", BUSINESS_DATE, LocalTime.of(22, 0, 0),
                new BigDecimal("13500"), new BigDecimal("108"), new BigDecimal("27"),
                "D", "APPR00000001", "A"));
        stagedRecordRepository.save(ZpStagedRecordEntity.zp0012Result(
                inboundId, 1, ZP_TXN_REF, BUSINESS_DATE,
                "00", new BigDecimal("13500")));
        stagedRecordRepository.flush();
        entityManager.clear();

        // File-scoped read-back, in file order.
        List<ZpStagedRecordEntity> outboundLines =
                stagedRecordRepository.findByBatchFileIdOrderByLineNumberAsc(outboundId);
        assertEquals(2, outboundLines.size());
        assertEquals(1, outboundLines.get(0).getLineNumber());
        assertEquals(2, outboundLines.get(1).getLineNumber());

        ZpStagedRecordEntity detail = outboundLines.get(0);
        assertEquals(ZpStagedRecordEntity.RECORD_TYPE_ZP0011, detail.getRecordType());
        assertEquals(outboundId, detail.getBatchFileId());
        assertEquals("GME0000000000000001", detail.getGmeTxnId());
        assertEquals("M0000001", detail.getMerchantId());
        assertEquals("QR0000000000000001", detail.getQrCodeId());
        assertEquals(BUSINESS_DATE, detail.getTxnDate());
        assertEquals(LocalTime.of(22, 0, 0), detail.getTxnTime());
        assertEquals(0, detail.getPayoutAmountKrw().compareTo(new BigDecimal("13500")));
        assertEquals(0, detail.getMerchantFeeAmtKrw().compareTo(new BigDecimal("108")));
        assertEquals(0, detail.getVanFeeAmtKrw().compareTo(new BigDecimal("27")));
        assertEquals("D", detail.getPartnerType());
        assertEquals("APPR00000001", detail.getApprovalCode());
        assertEquals("A", detail.getStatusCode());
        assertNull(detail.getResultCode(), "ZP0011 line carries no ZP0012 fields");
        assertNull(detail.getRegisteredAmountKrw());
        assertNotNull(detail.getCreatedAt());

        // Reconciliation match key (zeropay_txn_ref, txn_date) spans both files.
        List<ZpStagedRecordEntity> matched =
                stagedRecordRepository.findByZeropayTxnRefAndTxnDate(ZP_TXN_REF, BUSINESS_DATE);
        assertEquals(2, matched.size());

        ZpStagedRecordEntity zp0012 = matched.stream()
                .filter(r -> ZpStagedRecordEntity.RECORD_TYPE_ZP0012.equals(r.getRecordType()))
                .findFirst().orElseThrow();
        assertEquals(inboundId, zp0012.getBatchFileId());
        assertEquals("00", zp0012.getResultCode());
        ZpStagedRecordEntity zp0011 = matched.stream()
                .filter(r -> ZpStagedRecordEntity.RECORD_TYPE_ZP0011.equals(r.getRecordType()))
                .findFirst().orElseThrow();
        // Registered amount equals submitted payout — the no-mismatch case.
        assertEquals(0, zp0012.getRegisteredAmountKrw().compareTo(zp0011.getPayoutAmountKrw()));
    }

    @Test
    @DisplayName("duplicate (file_type, business_date, sequence_no) is rejected by the registry")
    void duplicateFileNaturalKeyRejected() {
        batchFileRepository.saveAndFlush(newOutboundZp0011File());

        ZpBatchFileEntity duplicate = ZpBatchFileEntity.outbound(
                "ZP0011", BUSINESS_DATE, 1, "ZP0011_20260609_001_retry.dat",
                SHA256_B, 100L, 0, BigDecimal.ZERO, WINDOW_OPENS, WINDOW_CLOSES);

        assertThrows(DataIntegrityViolationException.class,
                () -> batchFileRepository.saveAndFlush(duplicate));
    }

    @Test
    @DisplayName("staged record without an existing batch file is rejected (FK)")
    void stagedRecordRequiresExistingBatchFile() {
        ZpStagedRecordEntity orphan = ZpStagedRecordEntity.zp0012Result(
                9_999_999L, 1, ZP_TXN_REF, BUSINESS_DATE, "00", new BigDecimal("13500"));

        assertThrows(DataIntegrityViolationException.class,
                () -> stagedRecordRepository.saveAndFlush(orphan));
    }

    @Test
    @DisplayName("duplicate line_number within one file is rejected")
    void duplicateLineNumberWithinFileRejected() {
        Long fileId = batchFileRepository.save(newInboundZp0012File()).getId();
        stagedRecordRepository.saveAndFlush(ZpStagedRecordEntity.zp0012Result(
                fileId, 1, ZP_TXN_REF, BUSINESS_DATE, "00", new BigDecimal("13500")));

        ZpStagedRecordEntity sameLine = ZpStagedRecordEntity.zp0012Result(
                fileId, 1, "ZP20260609000099", BUSINESS_DATE, "00", new BigDecimal("1000"));

        assertThrows(DataIntegrityViolationException.class,
                () -> stagedRecordRepository.saveAndFlush(sameLine));
    }
}
