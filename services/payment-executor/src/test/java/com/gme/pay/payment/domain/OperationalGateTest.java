package com.gme.pay.payment.domain;

import com.gme.pay.contracts.OperationalStatusView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for the {@link OperationalGate} precedence + matching rules. */
class OperationalGateTest {

    private static OperationalGate gate(OperationalStatusView view) {
        return new OperationalGate(() -> view);
    }

    @Test
    @DisplayName("all-clear → new authorization proceeds")
    void allClear_proceeds() {
        assertDoesNotThrow(() ->
                gate(OperationalStatusView.allClear())
                        .checkNewAuthorization("GMEREMIT", "zeropay", "INBOUND"));
    }

    @Test
    @DisplayName("systemPaused → SYSTEM_PAUSED regardless of per-entity lists")
    void systemPaused_rejects() {
        OperationalStatusView v = new OperationalStatusView(
                true, false, List.of(), List.of(), List.of(), "incident", null);
        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> gate(v).checkNewAuthorization("GMEREMIT", "zeropay", "INBOUND"));
        assertEquals(OperationalGateException.SYSTEM_PAUSED, ex.code());
    }

    @Test
    @DisplayName("maintenanceMode → SYSTEM_PAUSED (softer flag, same canonical rejection)")
    void maintenance_rejects() {
        OperationalStatusView v = new OperationalStatusView(
                false, true, List.of(), List.of(), List.of(), null, null);
        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> gate(v).checkNewAuthorization("GMEREMIT", null, null));
        assertEquals(OperationalGateException.SYSTEM_PAUSED, ex.code());
    }

    @Test
    @DisplayName("suspended partner (case-insensitive) → PARTNER_SUSPENDED")
    void partnerSuspended_rejects() {
        OperationalStatusView v = new OperationalStatusView(
                false, false, List.of("gmeremit"), List.of(), List.of(), null, null);
        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> gate(v).checkNewAuthorization("GMEREMIT", "zeropay", "INBOUND"));
        assertEquals(OperationalGateException.PARTNER_SUSPENDED, ex.code());
    }

    @Test
    @DisplayName("suspended scheme → SCHEME_SUSPENDED")
    void schemeSuspended_rejects() {
        OperationalStatusView v = new OperationalStatusView(
                false, false, List.of(), List.of("zeropay"), List.of(), null, null);
        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> gate(v).checkNewAuthorization("GMEREMIT", "zeropay", "INBOUND"));
        assertEquals(OperationalGateException.SCHEME_SUSPENDED, ex.code());
    }

    @Test
    @DisplayName("suspended route → ROUTE_SUSPENDED")
    void routeSuspended_rejects() {
        OperationalStatusView v = new OperationalStatusView(
                false, false, List.of(), List.of(), List.of("fonepay.com"), null, null);
        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> gate(v).checkNewAuthorization("GMEREMIT", "NEPAL", "fonepay.com"));
        assertEquals(OperationalGateException.ROUTE_SUSPENDED, ex.code());
    }

    @Test
    @DisplayName("null references skip their per-entity check (partial resolution)")
    void nullRefs_skip() {
        OperationalStatusView v = new OperationalStatusView(
                false, false, List.of("OTHER"), List.of("other-scheme"), List.of(), null, null);
        assertDoesNotThrow(() -> gate(v).checkNewAuthorization(null, null, null));
    }
}
