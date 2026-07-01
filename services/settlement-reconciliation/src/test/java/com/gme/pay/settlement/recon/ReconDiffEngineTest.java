package com.gme.pay.settlement.recon;

import com.gme.pay.settlement.model.TransactionRecord;
import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.ReconExceptionEntity;
import com.gme.pay.settlement.persistence.ReconExceptionRepository;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link ReconDiffEngine}.
 *
 * <p>Uses Mockito to stub {@link TransactionQueryPort} and {@link ReconExceptionRepository}
 * so no Spring context or DB is needed.
 *
 * <p>Key scenarios:
 * <ul>
 *   <li>DISCREPANCY detected and persisted as exception row</li>
 *   <li>MISSING_SCHEME detected and persisted</li>
 *   <li>MATCHED lines are NOT persisted</li>
 *   <li>Field-name contract: TransactionRecord.merchantId() is used as the keying field</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReconDiffEngineTest {

    @Mock
    private TransactionQueryPort transactionQueryPort;

    @Mock
    private ReconExceptionRepository reconExceptionRepository;

    @Mock
    private SettlementBatchRepository batchRepository;

    @Mock
    private SettlementLineRepository lineRepository;

    private LineMatcher lineMatcher;
    private ReconDiffEngine engine;
    private ZP0062Parser zp0062Parser;

    private static final LocalDate DATE = LocalDate.of(2026, 6, 15);

    @BeforeEach
    void setUp() {
        lineMatcher = new LineMatcher();
        engine = new ReconDiffEngine(transactionQueryPort, lineMatcher, reconExceptionRepository,
                batchRepository, lineRepository, (ref, residual, ccy) -> true,
                new com.gme.pay.settlement.alert.ReconBreakAlerter(event -> {}));
        zp0062Parser = new ZP0062Parser();
        // lenient: some tests never trigger save (allMatched, fieldNameContract)
        lenient().when(reconExceptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TransactionRecord txn(String txnRef, String merchantId, BigDecimal amount) {
        return new TransactionRecord(
                1L, txnRef, "SCH-" + txnRef, merchantId,
                amount, 'N', new BigDecimal("0.008"),
                "APPROVED", OffsetDateTime.now(), null);
    }

    @Test
    @DisplayName("DISCREPANCY detected: GME=34720, scheme=34000 -> one exception row saved")
    void discrepancyDetectedAndPersisted() {
        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(List.of(
                txn("TXN-001", "MRC001", new BigDecimal("34720"))
        ));

        // ZP0062 says MRC001 got 34000 (not 34720)
        List<String> fileLines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034000",
                "EOF0000000001000000000000034000"
        );
        List<ZeroPayResultRecord> records = zp0062Parser.parse(fileLines);

        List<ReconLine> result = engine.runDiff("ZP0062-20260615", DATE, records);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(result.get(0).discrepancyAmount()).isEqualByComparingTo("720");

        // One exception row persisted
        ArgumentCaptor<ReconExceptionEntity> captor = ArgumentCaptor.forClass(ReconExceptionEntity.class);
        verify(reconExceptionRepository, times(1)).save(captor.capture());
        ReconExceptionEntity saved = captor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo("MRC001");
        assertThat(saved.getMatchStatus()).isEqualTo(MatchStatus.DISCREPANCY);
        assertThat(saved.getDiscrepancyAmount()).isEqualByComparingTo("720");
    }

    @Test
    @DisplayName("MISSING_SCHEME: GME has MRC002, scheme file has no MRC002 -> exception persisted")
    void missingScheme_exceptionPersisted() {
        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(List.of(
                txn("TXN-001", "MRC001", new BigDecimal("50000")),
                txn("TXN-002", "MRC002", new BigDecimal("25000"))
        ));

        // ZP0062 only has MRC001 — MRC002 is missing
        List<String> fileLines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000050000",
                "EOF0000000001000000000000050000"
        );
        List<ZeroPayResultRecord> records = zp0062Parser.parse(fileLines);

        List<ReconLine> result = engine.runDiff("ZP0062-20260615-B", DATE, records);

        assertThat(result).hasSize(2);

        long matched = result.stream().filter(l -> l.matchStatus() == MatchStatus.MATCHED).count();
        long missingScheme = result.stream().filter(l -> l.matchStatus() == MatchStatus.MISSING_SCHEME).count();
        assertThat(matched).isEqualTo(1);
        assertThat(missingScheme).isEqualTo(1);

        // Only the MISSING_SCHEME row is persisted (not the MATCHED)
        verify(reconExceptionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("All matched: no exception rows saved")
    void allMatched_noExceptionsSaved() {
        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(List.of(
                txn("TXN-001", "MRC001", new BigDecimal("34720"))
        ));

        List<String> fileLines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"
        );
        List<ZeroPayResultRecord> records = zp0062Parser.parse(fileLines);

        List<ReconLine> result = engine.runDiff("ZP0062-20260615-C", DATE, records);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.MATCHED);

        verify(reconExceptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Field-name contract: TransactionRecord.merchantId() used as key")
    void fieldNameContract_merchantIdIsKey() {
        // Verify the field name 'merchantId' is used (not 'merchant_id' or similar)
        TransactionRecord txn = txn("TXN-001", "MRC001", new BigDecimal("34720"));
        assertThat(txn.merchantId()).isEqualTo("MRC001");

        when(transactionQueryPort.findUnbatchedApproved(DATE)).thenReturn(List.of(txn));

        List<String> fileLines = List.of(
                "ZP006220260615001",
                "MRC001          0000000000034720",
                "EOF0000000001000000000000034720"
        );
        List<ZeroPayResultRecord> records = zp0062Parser.parse(fileLines);

        List<ReconLine> result = engine.runDiff("ZP0062-20260615-D", DATE, records);
        assertThat(result.get(0).matchStatus()).isEqualTo(MatchStatus.MATCHED);
    }
}
