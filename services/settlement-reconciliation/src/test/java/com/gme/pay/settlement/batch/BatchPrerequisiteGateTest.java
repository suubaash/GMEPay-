package com.gme.pay.settlement.batch;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.settlement.booking.SettlementBookingService;
import com.gme.pay.settlement.client.FixtureRefundedTransactionAdapter;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.persistence.SettlementLineRepository;
import com.gme.pay.settlement.port.PartnerConfigPort;
import com.gme.pay.settlement.port.RegistrationStatusPort;
import com.gme.pay.settlement.port.RegistrationStatusPort.RegistrationStatus;
import com.gme.pay.settlement.port.TransactionQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §8.2 / tickets 9.1-T18+T19: the ZP0061/ZP0063 settlement request is BLOCKED until the business
 * date's payment registration completed both legs (ZP0011 transmitted + ZP0012 received), and the
 * block happens BEFORE any batch row is created so nothing persists.
 */
class BatchPrerequisiteGateTest {

    private final TransactionQueryPort txnPort = mock(TransactionQueryPort.class);
    private final PartnerConfigPort partnerPort = mock(PartnerConfigPort.class);
    private final SettlementBookingService booking = new SettlementBookingService();
    private final SettlementBatchFactory factory = mock(SettlementBatchFactory.class);
    private final SettlementBatchRepository batchRepo = mock(SettlementBatchRepository.class);
    private final SettlementLineRepository lineRepo = mock(SettlementLineRepository.class);
    private final EventPublisher outbox = mock(EventPublisher.class);
    private final RegistrationStatusPort registrationPort = mock(RegistrationStatusPort.class);

    private SettlementBatchJobService job() {
        return new SettlementBatchJobService(txnPort, partnerPort, booking, factory, batchRepo,
                lineRepo, outbox, new FixtureRefundedTransactionAdapter(), registrationPort,
                com.gme.pay.settlement.calendar.BusinessCalendar.empty(), "", "");
    }

    @Test
    @DisplayName("registration incomplete -> BatchPrerequisiteException, nothing persisted")
    void blocksWindow_whenRegistrationIncomplete() {
        when(registrationPort.statusFor(any())).thenReturn(new RegistrationStatus(true, false));

        BatchPrerequisiteException ex = assertThrows(BatchPrerequisiteException.class,
                () -> job().runWindow("ZP0061", "MORNING"));

        assertTrue(ex.getMessage().contains("zp0012Received=false"), ex.getMessage());
        // Blocked BEFORE createOrGet: no batch row, no lines, no event — clean retry next window.
        verifyNoInteractions(factory);
        verifyNoInteractions(lineRepo);
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("ZP0011 never transmitted -> blocked even if a ZP0012 somehow exists")
    void blocksWindow_whenZp0011DidNotSucceed() {
        when(registrationPort.statusFor(any())).thenReturn(new RegistrationStatus(false, true));

        assertThrows(BatchPrerequisiteException.class, () -> job().runWindow("ZP0063", "AFTERNOON"));
        verifyNoInteractions(factory);
    }

    @Test
    @DisplayName("both legs complete -> the window proceeds past the gate")
    void allowsWindow_whenRegistrationComplete() {
        when(registrationPort.statusFor(any())).thenReturn(RegistrationStatus.allowed());
        when(txnPort.findUnbatchedApproved(any())).thenReturn(List.of());
        when(txnPort.findUnbatchedRefunded(any())).thenReturn(List.of());
        // Past the gate the factory is consulted; returning null ends the run right there,
        // which is all this test needs to prove (the full pipeline is covered elsewhere).
        when(factory.createOrGet(any(), any(), any())).thenReturn(null);

        assertThrows(RuntimeException.class, () -> job().runWindow("ZP0061", "MORNING"));
        org.mockito.Mockito.verify(factory).createOrGet(any(), any(), any());
    }
}
