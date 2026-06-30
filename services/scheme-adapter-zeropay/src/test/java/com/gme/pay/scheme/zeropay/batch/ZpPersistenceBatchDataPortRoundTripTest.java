package com.gme.pay.scheme.zeropay.batch;

import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnEntity;
import com.gme.pay.scheme.zeropay.persistence.ZpCommittedTxnRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end batch round-trip: captured committed txns → {@link ZpPersistenceBatchDataPort}
 * → {@link Zp0011FileFormatter} → {@link Zp0011FileParser}, proving the persistence-backed
 * source produces NON-EMPTY files (the gap left by {@code ZpStubBatchDataPort}) and that the
 * trailer control sum equals the sum of captured payout amounts.
 */
class ZpPersistenceBatchDataPortRoundTripTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 9);

    private final ZpCommittedTxnRepository repo = mock(ZpCommittedTxnRepository.class);
    private final ZpPersistenceBatchDataPort port = new ZpPersistenceBatchDataPort(repo);

    @Test
    void capturedPayments_produceNonEmptyZp0011_thatParsesBack() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of(
                        ZpCommittedTxnEntity.payment(
                                "GME0000000000000001", "ZP000000000000000001", "M000000001",
                                "QR000000000012345678", DATE, LocalTime.of(10, 30, 0),
                                new BigDecimal("50000"), new BigDecimal("250"),
                                new BigDecimal("100"), "D", "AP0000000001", null),
                        ZpCommittedTxnEntity.payment(
                                "GME0000000000000002", "ZP000000000000000002", "M000000002",
                                "QR000000000098765432", DATE, LocalTime.of(11, 45, 15),
                                new BigDecimal("120000"), new BigDecimal("600"),
                                new BigDecimal("200"), "I", "AP0000000002", null)));

        List<Zp0011Record> records = port.fetchPaymentRecords(DATE);
        assertEquals(2, records.size(), "data port must produce non-empty records (was the stub gap)");

        // Format then parse back.
        Zp0011FileFormatter formatter = new Zp0011FileFormatter("GMEPAY0001");
        Zp0011FileParser parser = new Zp0011FileParser();
        byte[] file = formatter.format(DATE, records);
        List<Zp0011Record> parsed = parser.parse(file);

        assertEquals(2, parsed.size());
        assertEquals("ZP000000000000000001", parsed.get(0).zeroPayTxnRef());
        assertEquals(0, parsed.get(0).payoutAmountKrw().compareTo(new BigDecimal("50000")));
        assertEquals('I', parsed.get(1).partnerType());

        // Trailer control sum (offset-checked) equals 50000 + 120000 = 170000.
        String[] lines = new String(file).split("\n");
        String trailer = lines[lines.length - 1];
        String controlSum = trailer.substring(
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET,
                Zp0011TrailerRecord.CONTROL_SUM_OFFSET + Zp0011TrailerRecord.CONTROL_SUM_LEN);
        assertEquals("000000000170000", controlSum);
    }

    @Test
    void emptyDay_stillProducesValidHeaderTrailerOnlyFile() {
        when(repo.findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                eq(DATE), eq(ZpCommittedTxnEntity.KIND_PAYMENT)))
                .thenReturn(List.of());

        List<Zp0011Record> records = port.fetchPaymentRecords(DATE);
        assertTrue(records.isEmpty());

        byte[] file = new Zp0011FileFormatter("GMEPAY0001").format(DATE, records);
        String[] lines = new String(file).split("\n");
        assertEquals(2, lines.length);  // header + trailer only
        assertTrue(lines[0].startsWith("ZP0011"));
    }
}
