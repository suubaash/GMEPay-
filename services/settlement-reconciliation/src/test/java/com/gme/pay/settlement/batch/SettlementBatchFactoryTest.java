package com.gme.pay.settlement.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Idempotency + re-generation behaviour of the outbound batch factory. */
class SettlementBatchFactoryTest {

    private final SettlementBatchRepository repo = mock(SettlementBatchRepository.class);
    private final SettlementBatchFactory factory = new SettlementBatchFactory(repo);
    private final LocalDate date = LocalDate.of(2026, 6, 18);

    @Test
    @DisplayName("no existing batch → inserts a fresh PENDING row with the idempotency-keyed id")
    void createsPending() {
        when(repo.findByFileTypeAndBusinessDateAndSettlementWindow("ZP0061", date, "MORNING"))
                .thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        SettlementBatchEntity b = factory.createOrGet("ZP0061", date, "MORNING");

        assertEquals("ZP0061-20260618-MORNING", b.getBatchId());
        assertEquals(SettlementBatchStatus.PENDING.name(), b.getStatus());
        assertEquals("GME_TO_ZP", b.getDirection());
        assertEquals("ZEROPAY", b.getPartnerId());
        verify(repo).save(any());
    }

    @Test
    @DisplayName("existing non-ERROR batch → returned as-is, no insert (idempotent)")
    void returnsExisting() {
        SettlementBatchEntity existing = new SettlementBatchEntity();
        existing.setBatchId("ZP0061-20260618-MORNING");
        existing.setStatus(SettlementBatchStatus.GENERATED.name());
        when(repo.findByFileTypeAndBusinessDateAndSettlementWindow("ZP0061", date, "MORNING"))
                .thenReturn(Optional.of(existing));

        assertSame(existing, factory.createOrGet("ZP0061", date, "MORNING"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("existing ERROR batch → replaced by a fresh PENDING row (re-generation)")
    void recreatesAfterError() {
        SettlementBatchEntity errored = new SettlementBatchEntity();
        errored.setStatus(SettlementBatchStatus.ERROR.name());
        when(repo.findByFileTypeAndBusinessDateAndSettlementWindow("ZP0061", date, "MORNING"))
                .thenReturn(Optional.of(errored));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        SettlementBatchEntity b = factory.createOrGet("ZP0061", date, "MORNING");

        assertEquals(SettlementBatchStatus.PENDING.name(), b.getStatus());
        verify(repo).save(any());
    }
}
