package com.gme.pay.payment.domain;

import com.gme.pay.contracts.SchemeAvailabilityVerdict;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.domain.client.SchemeOperatingHoursClient;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T3-6: the operating-hours gate's POLICY — the three-verdict decision and its side effects.
 *
 * <p>The window arithmetic itself is pinned in {@code SchemeAvailabilityTest} (lib-api-contracts); what
 * is asserted here is what the payment path DOES with each verdict, and that the choices this gap made
 * deliberately (UNVERIFIED permits + alerts; a cutoff never closes) actually hold.
 */
class SchemeOperatingHoursGateTest {

    /** 2026-07-28 is a TUESDAY → V024 weekday 1. 03:00Z = 12:00 KST. */
    private static final Instant TUE_NOON_KST = Instant.parse("2026-07-28T03:00:00Z");

    private static SchemeOperatingHoursView row(String scheme, int weekday, String open, String close,
                                                String cutoff, String zone) {
        return new SchemeOperatingHoursView(scheme, weekday, LocalTime.parse(open),
                LocalTime.parse(close), cutoff == null ? null : LocalTime.parse(cutoff), zone);
    }

    /** A client returning a fixed schedule for every scheme. */
    private static SchemeOperatingHoursClient clientReturning(List<SchemeOperatingHoursView> rows) {
        return schemeId -> rows;
    }

    private static SchemeOperatingHoursGate gate(SchemeOperatingHoursClient client,
                                                 OpsAlertPipeline alerts,
                                                 Instant now) {
        return new SchemeOperatingHoursGate(client, alerts, true,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ OPEN

    @Test
    @DisplayName("inside the window: the payment proceeds and nothing is alerted")
    void openWindow_permits_silently() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        SchemeOperatingHoursGate gate = gate(
                clientReturning(List.of(row("ZEROPAY", 1, "09:00", "18:00", null, "Asia/Seoul"))),
                alerts, TUE_NOON_KST);

        gate.checkNewPayment("ZEROPAY");

        verifyNoInteractions(alerts);
        assertEquals(SchemeAvailabilityVerdict.OPEN, gate.evaluate("ZEROPAY").verdict());
    }

    @Test
    @DisplayName("an adapter-shaped code (zeropay_kr) still resolves to the ZEROPAY schedule")
    void schemeCodeIsCanonicalised_soTheSeededScheduleIsActuallyConsulted() {
        // Without canonicalisation this would 404 at config-registry and the gate would report
        // UNVERIFIED forever — i.e. the seeded schedule would still have no effective consumer.
        List<String> asked = new ArrayList<>();
        SchemeOperatingHoursClient recording = schemeId -> {
            asked.add(schemeId);
            return List.of(row("ZEROPAY", 1, "18:00", "22:00", null, "Asia/Seoul"));
        };
        SchemeOperatingHoursGate gate = gate(recording, null, TUE_NOON_KST);

        SchemeClosedException closed =
                assertThrows(SchemeClosedException.class, () -> gate.checkNewPayment("zeropay_kr"));

        assertEquals("ZEROPAY", closed.schemeId());
        assertEquals(List.of("ZEROPAY"), asked,
                "the gate canonicalises before the read, so config-registry's roster-keyed URL matches");
    }

    // ---------------------------------------------------------------- CLOSED

    @Test
    @DisplayName("outside the window: SchemeClosedException carrying canonical SCHEME_CLOSED")
    void closedWindow_rejects_withCanonicalCode() {
        SchemeOperatingHoursGate gate = gate(
                clientReturning(List.of(row("ZEROPAY", 1, "18:00", "22:00", null, "Asia/Seoul"))),
                null, TUE_NOON_KST);

        SchemeClosedException ex =
                assertThrows(SchemeClosedException.class, () -> gate.checkNewPayment("ZEROPAY"));

        assertEquals(ErrorCode.SCHEME_CLOSED, ex.code());
        assertFalse(ErrorCode.SCHEME_CLOSED.retryable(), "closed-now is not retryable at this time");
        assertEquals(409, ErrorCode.SCHEME_CLOSED.httpStatus());
        assertTrue(ex.availability().closed());
        // The message must name the window and the scheme-LOCAL time, so a caller knows when it reopens.
        assertTrue(ex.getMessage().contains("18:00"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Asia/Seoul"), ex.getMessage());
    }

    @Test
    @DisplayName("timezone: the same instant is open for a Seoul scheme and closed for a New York one")
    void theSchemesOwnTimezoneDecides_notTheServers() {
        // 2026-07-27T23:30Z — MONDAY in New York (19:30), already TUESDAY in Seoul (08:30).
        Instant at = Instant.parse("2026-07-27T23:30:00Z");

        SchemeOperatingHoursGate seoulOpen = gate(
                clientReturning(List.of(row("ZEROPAY", 1, "08:00", "09:00", null, "Asia/Seoul"))),
                null, at);
        seoulOpen.checkNewPayment("ZEROPAY"); // does not throw

        SchemeOperatingHoursGate newYorkClosed = gate(
                clientReturning(List.of(row("ZEROPAY", 0, "09:00", "17:00", null, "America/New_York"))),
                null, at);
        SchemeClosedException ex = assertThrows(SchemeClosedException.class,
                () -> newYorkClosed.checkNewPayment("ZEROPAY"));
        assertTrue(ex.getMessage().contains("America/New_York"), ex.getMessage());
        assertEquals(0, ex.availability().weekday(), "the New-York-local weekday is still MONDAY");
    }

    // ----------------------------------------------------------- UNVERIFIED

    @Test
    @DisplayName("no seeded row: the payment PROCEEDS and a SCHEME_HOURS_UNVERIFIED alert is raised")
    void unverifiedWindow_permits_butIsObservable() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        SchemeOperatingHoursGate gate = gate(clientReturning(List.of()), alerts, TUE_NOON_KST);

        // NEPAL and SENDMN are live corridors with no V024 rows — blocking them would be the wrong call.
        gate.checkNewPayment("NEPAL");

        var captor = org.mockito.ArgumentCaptor.forClass(OpsAlertPayload.class);
        verify(alerts).emit(captor.capture());
        OpsAlertPayload alert = captor.getValue();
        assertEquals(SchemeOperatingHoursGate.ALERT_UNVERIFIED_WINDOW, alert.alertType());
        assertEquals("WARN", alert.severity());
        assertEquals("NEPAL", alert.subjectRef());
        assertTrue(alert.detail().contains("UNVERIFIED"), alert.detail());
    }

    @Test
    @DisplayName("the unverified alert is de-duplicated to one per (scheme, UTC date)")
    void unverifiedAlert_isDeduplicatedPerDay() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        SchemeOperatingHoursGate gate = gate(clientReturning(List.of()), alerts, TUE_NOON_KST);

        gate.checkNewPayment("NEPAL");
        gate.checkNewPayment("NEPAL");
        gate.checkNewPayment("NEPAL");
        gate.checkNewPayment("SENDMN");

        // One per scheme per day — a permanently unseeded corridor must not drown the alert stream,
        // and every individual payment still logs its own WARN line.
        verify(alerts, org.mockito.Mockito.times(2)).emit(any(OpsAlertPayload.class));
    }

    @Test
    @DisplayName("an unreachable schedule is UNVERIFIED (permitted + alerted), never a payment failure")
    void aThrowingClient_degradesToUnverified() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        SchemeOperatingHoursClient exploding = schemeId -> {
            throw new IllegalStateException("config-registry down");
        };
        SchemeOperatingHoursGate gate = gate(exploding, alerts, TUE_NOON_KST);

        gate.checkNewPayment("ZEROPAY"); // must not propagate

        assertEquals(SchemeAvailabilityVerdict.UNVERIFIED, gate.evaluate("ZEROPAY").verdict());
        verify(alerts).emit(any(OpsAlertPayload.class));
    }

    @Test
    @DisplayName("a failing alert sink never breaks the pay path")
    void alertFailure_isSwallowed() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(alerts).emit(any(OpsAlertPayload.class));
        SchemeOperatingHoursGate gate = gate(clientReturning(List.of()), alerts, TUE_NOON_KST);

        gate.checkNewPayment("NEPAL"); // does not throw
    }

    @Test
    @DisplayName("no scheme reference resolved: nothing is asserted and nothing is rejected")
    void nullSchemeRef_assertsNothing() {
        SchemeOperatingHoursClient never = schemeId -> {
            throw new AssertionError("must not be consulted without a scheme reference");
        };
        SchemeOperatingHoursGate gate = gate(never, null, TUE_NOON_KST);

        gate.checkNewPayment(null);
        gate.checkNewPayment("   ");
    }

    // --------------------------------------------------------------- CUTOFF

    @Test
    @DisplayName("cutoff is NOT a close: ZeroPay past its 16:30 KST cutoff is still OPEN")
    void pastSettlementCutoff_isNotARejection() {
        // 2026-07-28T09:00Z = 18:00 KST, past ZEROPAY's seeded 16:30 KFTC cutoff on a 24x7 rail.
        SchemeOperatingHoursGate gate = gate(
                clientReturning(List.of(
                        row("ZEROPAY", 1, "00:00:00", "23:59:59", "16:30", "Asia/Seoul"))),
                null, Instant.parse("2026-07-28T09:00:00Z"));

        gate.checkNewPayment("ZEROPAY"); // does not throw

        var availability = gate.evaluate("ZEROPAY");
        assertTrue(availability.open());
        assertTrue(availability.pastCutoff(), "the settlement-eligibility fact is still reported");
    }

    // ---------------------------------------------------- enforcement toggle

    @Test
    @DisplayName("enforcement-enabled=false allows a CLOSED scheme, and is not silent about it")
    void enforcementDisabled_allowsButBanners() {
        SchemeOperatingHoursGate gate = new SchemeOperatingHoursGate(
                clientReturning(List.of(row("ZEROPAY", 1, "18:00", "22:00", null, "Asia/Seoul"))),
                null, false, Clock.fixed(TUE_NOON_KST, ZoneOffset.UTC));

        // No throw despite a CLOSED verdict...
        gate.checkNewPayment("ZEROPAY");
        assertTrue(gate.evaluate("ZEROPAY").closed());
        // ...and the override announces itself at startup.
        assertTrue(SchemeOperatingHoursGate.DISABLED_BANNER.contains("enforcement-enabled=false"));
        assertTrue(SchemeOperatingHoursGate.DISABLED_BANNER.contains("CLOSED"));
    }

    @Test
    @DisplayName("a null status client path still evaluates the window (OperationalGate composition)")
    void operationalGate_composesTheWindowCheck_onBothEntryPoints() {
        // OperationalGate is the single place BOTH new-payment entry points call, which is why the
        // window check is composed into it rather than bolted onto each controller (the T4-2 split).
        SchemeOperatingHoursGate hours = gate(
                clientReturning(List.of(row("ZEROPAY", 1, "18:00", "22:00", null, "Asia/Seoul"))),
                null, TUE_NOON_KST);
        OperationalGate operational = new OperationalGate(
                () -> com.gme.pay.contracts.OperationalStatusView.allClear(), hours);

        assertThrows(SchemeClosedException.class,
                () -> operational.checkNewAuthorization("GMEREMIT", "ZEROPAY", null));

        // A null status (defensive branch) must still consult the window rather than returning early.
        OperationalGate nullStatus = new OperationalGate(() -> null, hours);
        assertThrows(SchemeClosedException.class,
                () -> nullStatus.checkNewAuthorization("GMEREMIT", "ZEROPAY", null));
    }

    @Test
    @DisplayName("an operator pause outranks a closed window (precedence)")
    void operatorHoldIsEvaluatedFirst() {
        SchemeOperatingHoursGate hours = gate(
                clientReturning(List.of(row("ZEROPAY", 1, "18:00", "22:00", null, "Asia/Seoul"))),
                null, TUE_NOON_KST);
        OperationalGate paused = new OperationalGate(
                () -> new com.gme.pay.contracts.OperationalStatusView(
                        true, false, List.of(), List.of(), List.of(), "maintenance", null),
                hours);

        OperationalGateException ex = assertThrows(OperationalGateException.class,
                () -> paused.checkNewAuthorization("GMEREMIT", "ZEROPAY", null));
        assertEquals(OperationalGateException.SYSTEM_PAUSED, ex.code());
    }

    @Test
    @DisplayName("a status-only OperationalGate (unit slices) performs no window check at all")
    void legacyOperationalGateConstructor_isUnchanged() {
        OperationalGate legacy =
                new OperationalGate(() -> com.gme.pay.contracts.OperationalStatusView.allClear());

        legacy.checkNewAuthorization("GMEREMIT", "ZEROPAY", null); // no throw, no lookup
    }

    @Test
    @DisplayName("refund/cancel/confirm are not gated: no gate method exists that they could call")
    void nothingGatesRefundsOrConfirms() {
        // The property is structural, not behavioural: OperationalGate's ONLY public entry points are
        // the two checkNewAuthorization overloads, and the window check lives inside them. There is no
        // checkRefund / checkConfirm / checkCancel to call, so a refund cannot be blocked by a closed
        // window even by accident. (The wallet + orchestrated controller-level proof that a refund and a
        // confirm still succeed while closed lives in WalletPayControllerTest / PaymentControllerTest.)
        List<String> gateEntryPoints = new ArrayList<>();
        for (var method : OperationalGate.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                gateEntryPoints.add(method.getName());
            }
        }
        assertEquals(List.of("checkNewAuthorization", "checkNewAuthorization"),
                gateEntryPoints.stream().sorted().toList(),
                "a new public gate entry point must be reviewed against the refund/confirm carve-out");
    }
}
