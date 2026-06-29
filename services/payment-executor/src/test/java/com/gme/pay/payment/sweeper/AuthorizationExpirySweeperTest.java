package com.gme.pay.payment.sweeper;

import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the expiry sweeper: it claims AUTHORIZED→EXPIRED atomically and voids only winners. */
class AuthorizationExpirySweeperTest {

    private final PaymentAuthorizationRepository repo = mock(PaymentAuthorizationRepository.class);
    private final PaymentAuthorizationService svc = mock(PaymentAuthorizationService.class);
    private final PaymentOrchestrator orchestrator = mock(PaymentOrchestrator.class);

    private final AuthorizationExpirySweeper sweeper =
            new AuthorizationExpirySweeper(repo, svc, orchestrator, 200);

    private static PaymentAuthorizationEntity authz(String authId, String txnRef) {
        PaymentAuthorizationEntity e = new PaymentAuthorizationEntity();
        e.setAuthId(authId);
        e.setPartnerId(900L);
        e.setPartnerType("OVERSEAS");
        e.setTxnRef(txnRef);
        e.setStatus(PaymentAuthorizationEntity.STATUS_AUTHORIZED);
        return e;
    }

    @Test
    void releasesExpiredHolds_andSkipsRowsAConfirmAlreadyClaimed() {
        PaymentAuthorizationEntity a1 = authz("AUTH-1", "txn-1");
        PaymentAuthorizationEntity a2 = authz("AUTH-2", "txn-2"); // a concurrent confirm grabbed this one
        when(repo.findByStatusAndExpiresAtBefore(
                eq(PaymentAuthorizationEntity.STATUS_AUTHORIZED), any(), any()))
                .thenReturn(List.of(a1, a2));
        when(svc.compareAndSetStatus("AUTH-1",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_EXPIRED)).thenReturn(true);
        when(svc.compareAndSetStatus("AUTH-2",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_EXPIRED)).thenReturn(false); // claim lost

        int swept = sweeper.sweepOnce(Instant.parse("2026-06-25T00:00:00Z"));

        assertEquals(1, swept, "only the row whose claim won should be released");
        // Winner: hold released + orphan txn failed.
        verify(orchestrator).voidAuthorization(900L, "txn-1", PartnerType.OVERSEAS);
        // Loser (confirm took it): never voided.
        verify(orchestrator, never()).voidAuthorization(eq(900L), eq("txn-2"), any());
    }

    @Test
    void noExpiredRows_isANoOp() {
        when(repo.findByStatusAndExpiresAtBefore(any(), any(), any())).thenReturn(List.of());
        assertEquals(0, sweeper.sweepOnce(Instant.now()));
        verify(orchestrator, never()).voidAuthorization(anyLong(), any(), any());
    }
}
