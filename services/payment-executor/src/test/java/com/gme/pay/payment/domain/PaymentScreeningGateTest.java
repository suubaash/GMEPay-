package com.gme.pay.payment.domain;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.kyb.NoProviderPaymentScreeningPort;
import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.persistence.UnscreenedPaymentCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * T5-3: the screening gate's POLICY.
 *
 * <p>What is pinned here is precisely the set of choices that could rot into a false claim of coverage:
 * that the no-provider default PERMITS but is never silent; that the alert is de-duplicated so the
 * unscreened stream stays readable; that fail-closed refuses with a non-retryable code before any side
 * effect; that an authoritative adverse verdict is refused even with fail-closed OFF; and — the one that
 * matters most — that <b>no configuration of this gate can produce a clean screening that did not
 * happen</b>.
 */
class PaymentScreeningGateTest {

    private static final Instant TUE = Instant.parse("2026-07-28T03:00:00Z");
    private static final Instant WED = Instant.parse("2026-07-29T03:00:00Z");

    /** The payer subject both real entry points actually produce: an opaque reference, no name. */
    private static final PaymentScreeningSubject OPAQUE_PAYER =
            PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, "wallet-user-42");

    private static PaymentScreeningGate gate(PaymentScreeningPort port,
                                             OpsAlertPipeline alerts,
                                             UnscreenedPaymentCounter counter,
                                             boolean failClosed,
                                             Instant now) {
        return new PaymentScreeningGate(port, alerts, counter, null, failClosed,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A provider that claims to be real and answers with the given status. */
    private static PaymentScreeningPort authoritative(ScreeningResult.Status status) {
        return new PaymentScreeningPort() {
            @Override
            public ScreeningResult screen(PaymentScreeningSubject subject) {
                List<ScreeningResult.Hit> hits = status == ScreeningResult.Status.CLEAR
                        ? List.of()
                        : List.of(new ScreeningResult.Hit("OFAC_SDN", "REDACTED", 0.97));
                return new ScreeningResult(status, hits, TUE, "vendor-ref-1",
                        ScreeningProvenance.vendor("acme-screening"));
            }

            @Override
            public String providerId() {
                return "acme-screening";
            }

            @Override
            public boolean authoritative() {
                return true;
            }
        };
    }

    // ------------------------------------------------- default: no provider

    @Test
    @DisplayName("no provider: the payment PROCEEDS, is COUNTED as NO_PROVIDER, and is alerted")
    void noProvider_permits_butIsCountedAndAlerted() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(), alerts, counter,
                false, TUE);

        // Must not throw: refusing here would take ZEROPAY / NEPAL / SENDMN down.
        gate.checkNewPayment("PTXN-1", "GMEREMIT", List.of(OPAQUE_PAYER));

        verify(counter).countUnscreened(eq(UnscreenedReason.NO_PROVIDER), eq(PaymentParty.PAYER),
                eq("none"), eq("GMEREMIT"), eq("PTXN-1"));

        ArgumentCaptor<OpsAlertPayload> captor = ArgumentCaptor.forClass(OpsAlertPayload.class);
        verify(alerts).emit(captor.capture());
        OpsAlertPayload alert = captor.getValue();
        assertEquals(PaymentScreeningGate.ALERT_UNSCREENED, alert.alertType());
        assertEquals("CRITICAL", alert.severity());
        assertEquals("PAYER", alert.subjectRef());
        assertTrue(alert.detail().contains("NO_PROVIDER"), alert.detail());
        // The alert must point at the answer to "how many?", not just report the condition.
        assertTrue(alert.detail().contains("/internal/ops/screening-coverage"), alert.detail());
    }

    @Test
    @DisplayName("the unscreened alert is de-duplicated per (reason, party, UTC date) — no per-payment flood")
    void unscreenedAlert_isDeduplicated_butEveryPaymentIsStillCounted() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(), alerts, counter,
                false, TUE);

        for (int i = 0; i < 50; i++) {
            gate.checkNewPayment("PTXN-" + i, "GMEREMIT", List.of(OPAQUE_PAYER));
        }

        // ONE alert for fifty payments: with no provider wired every payment is unscreened, so a
        // per-payment alert would fill ops_alerts and be ignored by humans.
        verify(alerts, times(1)).emit(any(OpsAlertPayload.class));
        // ...but the COUNT is per payment, which is what makes the total answerable.
        verify(counter, times(50)).countUnscreened(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a new UTC date re-alerts — the condition is re-surfaced daily, not silenced forever")
    void unscreenedAlert_reAlertsOnANewDay() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        gate(new NoProviderPaymentScreeningPort(), alerts, null, false, TUE)
                .checkNewPayment("A", "GMEREMIT", List.of(OPAQUE_PAYER));
        gate(new NoProviderPaymentScreeningPort(), alerts, null, false, WED)
                .checkNewPayment("B", "GMEREMIT", List.of(OPAQUE_PAYER));

        verify(alerts, times(2)).emit(any(OpsAlertPayload.class));
    }

    @Test
    @DisplayName("each party is counted separately — 'we screen the merchant but not the payer' is visible")
    void eachPartyIsCountedSeparately() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(),
                mock(OpsAlertPipeline.class), counter, false, TUE);

        gate.checkNewPayment("PTXN-1", "GMEREMIT", List.of(
                OPAQUE_PAYER,
                PaymentScreeningSubject.named(PaymentParty.BENEFICIARY, "M-1", "Acme Store")));

        verify(counter).countUnscreened(any(), eq(PaymentParty.PAYER), any(), any(), any());
        verify(counter).countUnscreened(any(), eq(PaymentParty.BENEFICIARY), any(), any(), any());
    }

    @Test
    @DisplayName("an EMPTY subject list is not a free pass — it is counted as NO_SUBJECT_IDENTITY-class gap")
    void noSubjectsAtAll_isStillCounted() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(),
                mock(OpsAlertPipeline.class), counter, false, TUE);

        gate.checkNewPayment("PTXN-1", "GMEREMIT", null);

        // "We were handed nobody to screen" must never be the quiet path.
        verify(counter).countUnscreened(any(), eq(PaymentParty.PAYER), eq("none"), eq("GMEREMIT"),
                eq("PTXN-1"));
    }

    // ------------------------------------------- provider wired, no identity

    @Test
    @DisplayName("a REAL provider still cannot screen an opaque reference: NO_SUBJECT_IDENTITY, provider never called")
    void realProvider_butNoName_isNotScreened() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningPort spy = mock(PaymentScreeningPort.class);
        org.mockito.Mockito.when(spy.authoritative()).thenReturn(true);
        org.mockito.Mockito.when(spy.providerId()).thenReturn("acme-screening");

        gate(spy, mock(OpsAlertPipeline.class), counter, false, TUE)
                .checkNewPayment("PTXN-1", "GMEREMIT", List.of(OPAQUE_PAYER));

        // THE finding of this gap: buying a vendor does not create coverage while the payment contract
        // carries no name. A name-matching provider handed only a customer reference can only answer
        // "no match", which is indistinguishable from clean — so it is never asked.
        verify(spy, never()).screen(any());
        verify(counter).countUnscreened(eq(UnscreenedReason.NO_SUBJECT_IDENTITY),
                eq(PaymentParty.PAYER), eq("acme-screening"), eq("GMEREMIT"), eq("PTXN-1"));
    }

    // ------------------------------------------------------ fail-closed OFF

    @Test
    @DisplayName("default (fail-closed OFF): an authoritative CLEAR proceeds silently — nothing counted, nothing alerted")
    void authoritativeClear_proceedsSilently() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);

        gate(authoritative(ScreeningResult.Status.CLEAR), alerts, counter, false, TUE)
                .checkNewPayment("PTXN-1", "GMEREMIT",
                        List.of(PaymentScreeningSubject.named(PaymentParty.PAYER, "C-1", "Jane Doe")));

        verifyNoInteractions(alerts);
        verifyNoInteractions(counter);
    }

    @Test
    @DisplayName("an authoritative HIT is REFUSED even with fail-closed OFF — the flag governs ignorance, not knowledge")
    void authoritativeHit_isRefusedRegardlessOfFailClosed() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        PaymentScreeningGate gate = gate(authoritative(ScreeningResult.Status.HIT), alerts, null,
                /* failClosed = */ false, TUE);

        PaymentScreeningRefusedException ex = assertThrows(PaymentScreeningRefusedException.class,
                () -> gate.checkNewPayment("PTXN-1", "GMEREMIT",
                        List.of(PaymentScreeningSubject.named(PaymentParty.PAYER, "C-1", "Jane Doe"))));

        assertEquals(PaymentScreeningRefusedException.SANCTIONS_HIT, ex.code());
        assertFalse(ex.retryable(), "a sanctions match needs a disposition, not a retry");

        ArgumentCaptor<OpsAlertPayload> captor = ArgumentCaptor.forClass(OpsAlertPayload.class);
        verify(alerts).emit(captor.capture());
        assertEquals(PaymentScreeningGate.ALERT_SANCTIONS_HIT, captor.getValue().alertType());
        // Tipping-off + PII: the matched name must not travel in the alert or the API message.
        assertFalse(captor.getValue().detail().contains("Jane Doe"), captor.getValue().detail());
        assertFalse(ex.getMessage().contains("Jane Doe"), ex.getMessage());
    }

    @Test
    @DisplayName("NEEDS_REVIEW is refused too — an undispositioned fuzzy match is not a pass")
    void authoritativeNeedsReview_isRefused() {
        PaymentScreeningGate gate = gate(authoritative(ScreeningResult.Status.NEEDS_REVIEW),
                mock(OpsAlertPipeline.class), null, false, TUE);

        assertEquals(PaymentScreeningRefusedException.SANCTIONS_HIT,
                assertThrows(PaymentScreeningRefusedException.class,
                        () -> gate.checkNewPayment("P", "GMEREMIT", List.of(
                                PaymentScreeningSubject.named(PaymentParty.PAYER, "C", "Jane Doe"))))
                        .code());
    }

    // ------------------------------------------------------- fail-closed ON

    @Test
    @DisplayName("fail-closed ON + no provider: REFUSED with a structured, non-retryable code")
    void failClosed_refusesUnscreenedPayments() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(), alerts, counter,
                /* failClosed = */ true, TUE);

        PaymentScreeningRefusedException ex = assertThrows(PaymentScreeningRefusedException.class,
                () -> gate.checkNewPayment("PTXN-1", "GMEREMIT", List.of(OPAQUE_PAYER)));

        assertEquals(PaymentScreeningRefusedException.SCREENING_UNAVAILABLE, ex.code());
        assertFalse(ex.retryable(), "a missing vendor is not cured by retrying");
        // The message must name the CAUSE, because the four reasons have four different owners.
        assertTrue(ex.getMessage().contains("NO_PROVIDER"), ex.getMessage());
        // Refusing does not skip the accounting: the payment is still counted and alerted.
        verify(counter).countUnscreened(eq(UnscreenedReason.NO_PROVIDER), any(), any(), any(), any());
        verify(alerts).emit(any(OpsAlertPayload.class));
    }

    // --------------------------------------------- a provider that misbehaves

    @Test
    @DisplayName("a throwing provider is PROVIDER_ERROR — never a silent pass")
    void throwingProvider_isCountedAsProviderError() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        PaymentScreeningPort broken = new PaymentScreeningPort() {
            @Override
            public ScreeningResult screen(PaymentScreeningSubject subject) {
                throw new IllegalStateException("vendor timeout");
            }

            @Override
            public String providerId() {
                return "acme-screening";
            }

            @Override
            public boolean authoritative() {
                return true;
            }
        };

        gate(broken, mock(OpsAlertPipeline.class), counter, false, TUE)
                .checkNewPayment("P", "GMEREMIT",
                        List.of(PaymentScreeningSubject.named(PaymentParty.PAYER, "C", "Jane Doe")));

        verify(counter).countUnscreened(eq(UnscreenedReason.PROVIDER_ERROR), eq(PaymentParty.PAYER),
                eq("acme-screening"), any(), any());
    }

    @Test
    @DisplayName("a provider claiming CLEAR without authoritative provenance cannot produce a pass")
    void nonAuthoritativeClear_cannotMasqueradeAsScreened() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        // A mis-wired adapter: it says it is authoritative, but stamps a non-authoritative provenance.
        PaymentScreeningPort misWired = new PaymentScreeningPort() {
            @Override
            public ScreeningResult screen(PaymentScreeningSubject subject) {
                return new ScreeningResult(ScreeningResult.Status.CLEAR, List.of(), TUE, "ref",
                        ScreeningProvenance.nonAuthoritative("acme-cache", "served from a stale cache"));
            }

            @Override
            public String providerId() {
                return "acme-screening";
            }

            @Override
            public boolean authoritative() {
                return true;
            }
        };

        gate(misWired, mock(OpsAlertPipeline.class), counter, false, TUE)
                .checkNewPayment("P", "GMEREMIT",
                        List.of(PaymentScreeningSubject.named(PaymentParty.PAYER, "C", "Jane Doe")));

        // The lib-kyb ScreeningResult constructor coerced CLEAR -> NOT_SCREENED_NO_PROVIDER, so the
        // gate counted it as a gap instead of letting it through as clean. This is the T1-4 guarantee
        // being inherited by the payment path, and it is why lib-kyb's types were reused rather than
        // re-declared.
        verify(counter).countUnscreened(eq(UnscreenedReason.PROVIDER_NOT_AUTHORITATIVE),
                eq(PaymentParty.PAYER), eq("acme-screening"), any(), any());
    }

    // ---------------------------------------------------------- the posture

    @Test
    @DisplayName("the gate reports its own posture honestly")
    void posture_isReportedHonestly() {
        PaymentScreeningGate none = gate(new NoProviderPaymentScreeningPort(), null, null, false, TUE);
        assertFalse(none.screeningActive());
        assertEquals("none", none.providerId());
        assertFalse(none.failClosed());

        PaymentScreeningGate real = gate(authoritative(ScreeningResult.Status.CLEAR), null, null,
                true, TUE);
        assertTrue(real.screeningActive());
        assertEquals("acme-screening", real.providerId());
        assertTrue(real.failClosed());
    }

    @Test
    @DisplayName("the startup banners name the missing control and the kill switch")
    void startupBanners_nameTheMissingControlAndTheSwitch() {
        // Pinned as text, not as a log framework: the whole value of the banner is that a human reading
        // a container log learns WHAT is absent and WHICH knob changes it.
        assertTrue(PaymentScreeningGate.NO_PROVIDER_BANNER.contains("NO TRANSACTION SANCTIONS/PEP"));
        assertTrue(PaymentScreeningGate.NO_PROVIDER_BANNER.contains("T5-3"));
        assertTrue(PaymentScreeningGate.NO_PROVIDER_BANNER.contains("gmepay.screening.fail-closed"));
        assertTrue(PaymentScreeningGate.NO_PROVIDER_BANNER.contains("screening-coverage"));
        assertTrue(PaymentScreeningGate.FAIL_CLOSED_BANNER.contains("REFUSED"));

        // The banner must be reachable, i.e. actually fire for the default port.
        PaymentScreeningGate gate = gate(new NoProviderPaymentScreeningPort(), null, null, true, TUE);
        gate.bannerAtStartup();
        assertFalse(gate.screeningActive());
    }

    @Test
    @DisplayName("a null port is rejected: the honest no-provider state is a PORT, not an absent gate")
    void nullPort_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentScreeningGate(null, null, null, null, false, Clock.systemUTC()));
    }

    // -------------------------------------------------- alerting is never fatal

    @Test
    @DisplayName("a failing alert pipeline does not break the payment")
    void alertFailure_neverBreaksThePayPath() {
        OpsAlertPipeline exploding = mock(OpsAlertPipeline.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(exploding).emit(any(OpsAlertPayload.class));

        gate(new NoProviderPaymentScreeningPort(), exploding, null, false, TUE)
                .checkNewPayment("P", "GMEREMIT", List.of(OPAQUE_PAYER));
        // no exception
    }

    @Test
    @DisplayName("the no-provider port can never report CLEAR, whatever it is handed")
    void noProviderPort_neverReportsClear() {
        NoProviderPaymentScreeningPort port = new NoProviderPaymentScreeningPort();
        for (PaymentParty party : PaymentParty.values()) {
            ScreeningResult r = port.screen(PaymentScreeningSubject.named(party, "R", "Jane Doe"));
            assertEquals(ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER, r.status());
            assertFalse(r.authoritative());
            assertFalse(r.screeningPerformed());
            assertTrue(r.caveat().contains("NOT SCREENED"), r.caveat());
        }
        assertFalse(port.authoritative());
        assertEquals(ScreeningProvenance.NO_PROVIDER_ID, port.providerId());
    }

    @Test
    @DisplayName("the counter is optional — a gate with no persistence still alerts and still permits")
    void counterIsOptional() {
        OpsAlertPipeline alerts = mock(OpsAlertPipeline.class);
        gate(new NoProviderPaymentScreeningPort(), alerts, null, false, TUE)
                .checkNewPayment(null, null, List.of(OPAQUE_PAYER));
        verify(alerts).emit(any(OpsAlertPayload.class));
    }

    @Test
    @DisplayName("a null paymentRef / partnerRef is carried through, not defaulted into a fake value")
    void nullRefs_areCarriedThrough() {
        UnscreenedPaymentCounter counter = mock(UnscreenedPaymentCounter.class);
        gate(new NoProviderPaymentScreeningPort(), null, counter, false, TUE)
                .checkNewPayment(null, null, List.of(OPAQUE_PAYER));
        // The wallet path genuinely has neither at gate time; inventing one would fabricate evidence.
        verify(counter).countUnscreened(eq(UnscreenedReason.NO_PROVIDER), eq(PaymentParty.PAYER),
                eq("none"), isNull(), isNull());
    }
}
