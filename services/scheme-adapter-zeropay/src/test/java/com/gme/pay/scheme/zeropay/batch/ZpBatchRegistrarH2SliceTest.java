package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.adapter.model.BatchFile;
import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpBatchFileRepository;
import com.gme.pay.scheme.zeropay.persistence.ZpStagedRecordEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpStagedRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * H2 slice test for {@link ZpBatchRegistrar}: a generated ZP0011 is registered (GENERATED),
 * its detail lines are staged, the natural-key idempotency holds, and the transmit-mark flips
 * the status. Runs against H2 in PostgreSQL mode (no Docker).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ZpBatchRegistrar.class)
class ZpBatchRegistrarH2SliceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 9);

    @Autowired private ZpBatchRegistrar registrar;
    @Autowired private ZpBatchFileRepository batchFileRepository;
    @Autowired private ZpStagedRecordRepository stagedRecordRepository;

    private static BatchFile zp0011File(int recordCount, BigDecimal controlSum) {
        return new BatchFile(BatchType.ZP0011, DATE, 1, "content".getBytes(),
                recordCount, controlSum);
    }

    private static Zp0011Record record(String gmeId, String ref, String amount) {
        return new Zp0011Record(
                gmeId, ref, "M0000001", "QR" + ref, DATE, LocalTime.of(10, 0, 0),
                new BigDecimal(amount), new BigDecimal("250"), new BigDecimal("100"),
                'D', "AP" + ref, 'A');
    }

    @Test
    @DisplayName("registerGenerated persists the file row + staged ZP0011 lines; markTransmitted flips status")
    void registerGeneratedAndTransmit() {
        List<Zp0011Record> records = List.of(
                record("GME0000000000000001", "ZP0000000000000001", "50000"),
                record("GME0000000000000002", "ZP0000000000000002", "30000"));
        Long id = registrar.registerGenerated(
                zp0011File(2, new BigDecimal("80000")), "ZP0011_20260609_001.dat",
                Instant.parse("2026-06-09T17:00:00Z"), Instant.parse("2026-06-09T18:00:00Z"),
                records);

        assertNotNull(id);
        ZpBatchFileEntity file = batchFileRepository.findById(id).orElseThrow();
        assertEquals("ZP0011", file.getFileType());
        assertEquals(ZpBatchFileEntity.STATUS_GENERATED, file.getStatus());
        assertEquals(2, file.getRecordCount());
        assertEquals(0, file.getControlSumKrw().compareTo(new BigDecimal("80000")));
        assertEquals(64, file.getSha256Checksum().length());

        List<ZpStagedRecordEntity> staged =
                stagedRecordRepository.findByBatchFileIdOrderByLineNumberAsc(id);
        assertEquals(2, staged.size());
        assertEquals(1, staged.get(0).getLineNumber());
        assertEquals("ZP0000000000000001", staged.get(0).getZeropayTxnRef());
        assertEquals(0, staged.get(0).getPayoutAmountKrw().compareTo(new BigDecimal("50000")));

        registrar.markTransmitted(id, Instant.parse("2026-06-09T17:05:00Z"));
        ZpBatchFileEntity after = batchFileRepository.findById(id).orElseThrow();
        assertEquals(ZpBatchFileEntity.STATUS_TRANSMITTED, after.getStatus());
        assertEquals(Instant.parse("2026-06-09T17:05:00Z"), after.getSentAt());
    }

    @Test
    @DisplayName("registerGenerated is idempotent on the natural key — no duplicate row")
    void registerGeneratedIsIdempotent() {
        Long first = registrar.registerGenerated(
                zp0011File(0, BigDecimal.ZERO), "ZP0011_20260609_001.dat",
                Instant.now(), Instant.now().plusSeconds(3600), List.of());
        Long second = registrar.registerGenerated(
                zp0011File(0, BigDecimal.ZERO), "ZP0011_20260609_001_retry.dat",
                Instant.now(), Instant.now().plusSeconds(3600), List.of());

        assertEquals(first, second);
        assertEquals(1, batchFileRepository
                .findByBusinessDateAndDirection(DATE, ZpBatchFileEntity.DIRECTION_OUTBOUND).size());
    }
}
