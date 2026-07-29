package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PartnerConfigClient.TxnLimits;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import com.gme.pay.payment.persistence.ExecutionAttemptEntity;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-2 — regulatory limits on the WALLET path ({@code POST /v1/pay}).
 *
 * <p>Before this, {@code /v1/pay} enforced nothing: no per-transaction ceiling, no rolling
 * daily/monthly/annual cap, no AML velocity count — on any of the three corridors. These tests pin
 * the closed hole at the money path itself (the services + the failover router the controller
 * dispatches to), because that is where the float moves and the scheme is called.
 *
 * <p>The invariant every rejection test asserts: <b>no scheme call and no prefunding movement</b>.
 * A cap breach must leave no trace beyond the recorded attempt.
 *
 * <p>USD basis: 1 USD = 1350 KRW throughout (the live rate the corridors use for their prefunding
 * deduction — {@link UsdAmountBasis}), so 8,100,000 KRW ≈ 6,000 USD, over the statutory
 * 소액해외송금업 per-transaction ceiling of 5,000 USD.
 */
class WalletLimitEnforcementTest {

    /** 1 USD = 1350 KRW — the corridors' USD basis. */
    private static final BigDecimal KRW_PER_USD = new BigDecimal("1350");
    /** 8.1M KRW ≈ 6,000 USD — over the 5,000 USD SOAEK_HAEOEMONG per-txn ceiling. */
    private static final BigDecimal OVER_CAP_KRW = new BigDecimal("8100000");
    /** 1.35M KRW = 1,000 USD — comfortably inside every cap used here. */
    private static final BigDecimal WITHIN_CAP_KRW = new BigDecimal("1350000");

    private QrClient qrClient;
    private RateClient rateClient;
    private PrefundingClient prefundingClient;
    private SchemeClient schemeClient;
    private ExecutionAttemptRepository attemptRepository;
    private PartnerConfigClient partnerConfigClient;

    @BeforeEach
    void setUp() {
        qrClient = mock(QrClient.class);
        rateClient = mock(RateClient.class);
        prefundingClient = mock(PrefundingClient.class);
        schemeClient = mock(SchemeClient.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);
        partnerConfigClient = mock(PartnerConfigClient.class);

        when(qrClient.resolve(anyString())).thenReturn(
                new QrClient.MerchantView("M001", "Merchant", "KRW", "zeropay", "RETAIL", true));
        when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(
                new RateClient.LiveRate("USD", "KRW", KRW_PER_USD, Instant.now(), "sim"));
        when(rateClient.fetchLiveRate("KRW", "MNT")).thenReturn(
                new RateClient.LiveRate("KRW", "MNT", new BigDecimal("3.5"), Instant.now(), "sim"));
        when(rateClient.fetchLiveRate("USD", "NPR")).thenReturn(
                new RateClient.LiveRate("USD", "NPR", new BigDecimal("135"), Instant.now(), "sim"));
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("AP-1", "SCHEME-TXN-1", Instant.now()));
        when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                new PrefundingClient.DeductionResult(BigDecimal.ONE, new BigDecimal("100")));
    }

    // ---- limit fixtures -------------------------------------------------------------------

    /** The statutory 소액해외송금업 regime: DB-hard-capped 5,000 USD per txn / 50,000 USD annual (V020). */
    private static TxnLimits soaekHaeoemong() {
        return new TxnLimits(null, new BigDecimal("5000"), null, null,
                new BigDecimal("50000"), "SOAEK_HAEOEMONG", null);
    }

    private static TxnLimits dailyCapOnly(String dailyCapUsd) {
        return new TxnLimits(null, null, new BigDecimal(dailyCapUsd), null, null, "SOAEK_HAEOEMONG", null);
    }

    private static TxnLimits velocityOnly(int dailyTxnCountLimit) {
        return new TxnLimits(null, null, null, null, null, "SOAEK_HAEOEMONG", dailyTxnCountLimit);
    }

    private void limitsFor(String partnerCode, TxnLimits limits) {
        when(partnerConfigClient.resolveLimits(partnerCode)).thenReturn(Optional.ofNullable(limits));
    }

    private WalletLimitGate gate() {
        return new WalletLimitGate(partnerConfigClient, prefundingClient, rateClient);
    }

    /** The prefunding cumulative charge trips (prefunding evaluates the caps under its row lock). */
    private void cumulativeBreach() {
        doThrow(new CumulativeLimitExceededException("cap breached"))
                .when(prefundingClient).chargeCumulative(anyLong(), anyString(), any(),
                        any(), any(), any(), any());
    }

    private void assertNoSchemeCallAndNoFloatMoved() {
        verify(schemeClient, never()).submitMpm(any());
        verify(prefundingClient, never()).deduct(anyLong(), anyString(), any());
        verify(prefundingClient, never()).reserve(anyLong(), anyString(), any());
    }

    /** The decline must be recorded the way other declines are (FAILED attempt row). */
    private void assertFailedAttemptPersisted() {
        ArgumentCaptor<ExecutionAttemptEntity> captor =
                ArgumentCaptor.forClass(ExecutionAttemptEntity.class);
        verify(attemptRepository).save(captor.capture());
        assertEquals(PaymentStatus.FAILED, captor.getValue().getOutcome(),
                "a limit refusal must be persisted as a FAILED attempt");
    }

    // =======================================================================================
    // SENDMN (KRW→MNT, real prefunding deduction)
    // =======================================================================================

    @Nested
    @DisplayName("SENDMN corridor")
    class Sendmn {

        private SendmnPaymentService service() {
            return new SendmnPaymentService(qrClient, rateClient, prefundingClient, schemeClient,
                    attemptRepository, /* lenient */ true, new BigDecimal("0.02"),
                    null, null, null, gate());
        }

        @Test
        @DisplayName("per-txn max exceeded → declined, NO scheme call, NO prefund movement")
        void perTxnMaxExceeded() {
            limitsFor("SENDMN", soaekHaeoemong());

            TransactionLimitExceededException ex = assertThrows(
                    TransactionLimitExceededException.class,
                    () -> service().pay("QPAY_QR", OVER_CAP_KRW, "user-mn", 2L));

            assertTrue(ex.getMessage().contains("per-transaction MAX limit of 5000"), ex.getMessage());
            assertNoSchemeCallAndNoFloatMoved();
            assertFailedAttemptPersisted();
        }

        @Test
        @DisplayName("daily cap exceeded → declined, NO scheme call, NO prefund movement")
        void dailyCapExceeded() {
            limitsFor("SENDMN", dailyCapOnly("100"));
            cumulativeBreach();

            assertThrows(CumulativeLimitExceededException.class,
                    () -> service().pay("QPAY_QR", WITHIN_CAP_KRW, "user-mn", 2L));

            assertNoSchemeCallAndNoFloatMoved();
            assertFailedAttemptPersisted();
        }

        @Test
        @DisplayName("velocity (daily txn count) cap is passed to prefunding and a breach declines")
        void velocityCapExceeded() {
            limitsFor("SENDMN", velocityOnly(3));
            cumulativeBreach();

            assertThrows(CumulativeLimitExceededException.class,
                    () -> service().pay("QPAY_QR", WITHIN_CAP_KRW, "user-mn", 2L));

            // The velocity cap must actually reach prefunding — it is evaluated there (V034).
            verify(prefundingClient).chargeCumulative(eq(2L), anyString(), any(),
                    eq(null), eq(null), eq(null), eq(3));
            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("within limits → APPROVED; cumulative charged on the SAME USD as the deduct")
        void withinLimitsApproves() {
            limitsFor("SENDMN", soaekHaeoemong());

            WalletResult result = service().pay("QPAY_QR", new BigDecimal("10000"), "user-mn", 2L);

            assertTrue(result.approved(), "a within-limits SENDMN payment must still approve");
            // chargedUsd = (10000 + 500 fee) / 1350 = 7.77777778 — the limit basis IS the money basis.
            BigDecimal expectedUsd = new BigDecimal("7.77777778");
            verify(prefundingClient).chargeCumulative(eq(2L), anyString(), eq(expectedUsd),
                    eq(null), eq(null), eq(new BigDecimal("50000")), eq(null));
            verify(prefundingClient).deduct(eq(2L), anyString(), eq(expectedUsd));
        }

        @Test
        @DisplayName("no limits configured → unconstrained, cumulative ledger never touched")
        void noLimitsConfigured() {
            limitsFor("SENDMN", null);

            assertTrue(service().pay("QPAY_QR", OVER_CAP_KRW, "user-mn", 2L).approved());

            verify(prefundingClient, never()).chargeCumulative(anyLong(), anyString(), any(),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("scheme decline after a cumulative charge → cap returned (not permanently consumed)")
        void schemeDeclineReturnsCap() {
            limitsFor("SENDMN", dailyCapOnly("100000"));
            when(schemeClient.submitMpm(any()))
                    .thenThrow(new SchemeDeclinedException("SENDMN_DECLINED", "nope"));

            WalletResult result = service().pay("QPAY_QR", new BigDecimal("10000"), "user-mn", 2L);

            assertTrue(!result.approved());
            verify(prefundingClient).reverseCumulative(eq(2L), anyString());
        }
    }

    // =======================================================================================
    // GMEREMIT (domestic KRW→KRW, no prefunding float)
    // =======================================================================================

    @Nested
    @DisplayName("GMEREMIT domestic corridor")
    class Gmeremit {

        private GmeremitPaymentService service() {
            return new GmeremitPaymentService(qrClient, schemeClient, attemptRepository,
                    /* devSynthMerchant */ false, gate());
        }

        @Test
        @DisplayName("per-txn max exceeded → declined, NO scheme call")
        void perTxnMaxExceeded() {
            limitsFor("GMEREMIT", soaekHaeoemong());

            assertThrows(TransactionLimitExceededException.class,
                    () -> service().pay("ZPQR1", OVER_CAP_KRW, "user-kr"));

            assertNoSchemeCallAndNoFloatMoved();
            assertFailedAttemptPersisted();
        }

        @Test
        @DisplayName("daily cap exceeded → declined, NO scheme call")
        void dailyCapExceeded() {
            limitsFor("GMEREMIT", dailyCapOnly("500"));
            cumulativeBreach();

            assertThrows(CumulativeLimitExceededException.class,
                    () -> service().pay("ZPQR1", WITHIN_CAP_KRW, "user-kr"));

            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("velocity cap exceeded → declined, NO scheme call")
        void velocityCapExceeded() {
            limitsFor("GMEREMIT", velocityOnly(1));
            cumulativeBreach();

            assertThrows(CumulativeLimitExceededException.class,
                    () -> service().pay("ZPQR1", WITHIN_CAP_KRW, "user-kr"));

            verify(prefundingClient).chargeCumulative(eq(1L), anyString(), any(),
                    eq(null), eq(null), eq(null), eq(1));
            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("within limits → APPROVED; USD basis is amountKrw / live USD-KRW rate")
        void withinLimitsApproves() {
            limitsFor("GMEREMIT", dailyCapOnly("100000"));

            WalletResult result = service().pay("ZPQR1", WITHIN_CAP_KRW, "user-kr");

            assertTrue(result.approved(), "a within-limits domestic payment must still approve");
            // 1,350,000 KRW / 1350 = 1000.00000000 USD — the platform's single USD basis.
            verify(prefundingClient).chargeCumulative(eq(1L), anyString(),
                    eq(new BigDecimal("1000.00000000")),
                    eq(new BigDecimal("100000")), eq(null), eq(null), eq(null));
        }

        @Test
        @DisplayName("the 5,000 / 50,000 USD SOAEK_HAEOEMONG regime is honoured on the wallet path")
        void soaekHardCapsHonoured() {
            limitsFor("GMEREMIT", soaekHaeoemong());

            // 6,000 USD equivalent — over the statutory per-transaction ceiling.
            assertThrows(TransactionLimitExceededException.class,
                    () -> service().pay("ZPQR1", OVER_CAP_KRW, "user-kr"));
            verify(schemeClient, never()).submitMpm(any());

            // A within-ceiling payment still runs, and charges the 50,000 USD annual cap.
            assertTrue(service().pay("ZPQR1", WITHIN_CAP_KRW, "user-kr").approved());
            verify(prefundingClient).chargeCumulative(eq(1L), anyString(), any(),
                    eq(null), eq(null), eq(new BigDecimal("50000")), eq(null));
        }

        @Test
        @DisplayName("scheme decline after a cumulative charge → cap returned")
        void schemeDeclineReturnsCap() {
            limitsFor("GMEREMIT", dailyCapOnly("100000"));
            when(schemeClient.submitMpm(any()))
                    .thenThrow(new SchemeDeclinedException("ZP_DECLINED", "nope"));

            assertTrue(!service().pay("ZPQR1", WITHIN_CAP_KRW, "user-kr").approved());

            verify(prefundingClient).reverseCumulative(eq(1L), anyString());
        }
    }

    // =======================================================================================
    // Nepal — the LIVE path is the failover router, which since T4-1 DELEGATES the corridor to
    // NepalPaymentService (its single money path). So the router test below exercises the real
    // production chain: /v1/pay → FailoverPaymentRouter → NepalPaymentService → gate.
    //
    // The T4-2 basis also changed with T4-1 and is now STRONGER: the gate no longer converts a
    // pass-through NPR amount at USD/NPR, it runs on the corridor's own fee-inclusive `chargedUsd`
    // (enforceUsd), i.e. bit-for-bit the figure the prefunding deduct moves.
    // =======================================================================================

    @Nested
    @DisplayName("Nepal (KRW→NPR) corridor")
    class Nepal {

        /** A Fonepay MPM QR: classifies to fonepay.com / NP. */
        private static final String FONEPAY_QR =
                "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

        /** Owner-configured corridor terms (T4-1 refuses without them). */
        private static final String MARGIN = "0.02";
        private static final String FEE_KRW = "500";

        /**
         * Corridor-specific stubs. Kept in a nested {@code @BeforeEach} (not inside the helpers) so a
         * test that wants a rate to FAIL can re-stub it afterwards — re-stubbing a
         * {@code thenThrow} mock inside a helper would trigger the throw during stubbing.
         */
        @BeforeEach
        void nepalStubs() {
            when(rateClient.fetchLiveRate("KRW", "NPR")).thenReturn(
                    new RateClient.LiveRate("KRW", "NPR", new BigDecimal("0.10"), Instant.now(), "sim"));
            // The Nepal adapter maps its state onto schemeApprovalCode, so an approval reads "APPROVED"
            // (the outer setUp's ZeroPay-shaped "AP-1" is not one).
            when(schemeClient.submitMpm(any())).thenReturn(
                    new SchemeClient.MpmSubmitResponse("APPROVED", "NP-1", Instant.now()));
        }

        private SmartRouterClient router() {
            SmartRouterClient r = mock(SmartRouterClient.class);
            when(r.resolve(anyString(), any(), anyString(), any())).thenReturn(
                    List.of(new SmartRouterClient.PartnerSchemeView(1L, "Nepal", "NEPAL", 0)));
            return r;
        }

        private NepalPaymentService corridor() {
            return new NepalPaymentService(schemeClient, attemptRepository,
                    new NepalCorridorPricing(rateClient, partnerConfigClient, MARGIN, FEE_KRW),
                    prefundingClient, gate());
        }

        /** The production chain: the failover router delegating the Nepal corridor. */
        private FailoverPaymentRouter failover() {
            return new FailoverPaymentRouter(router(), schemeClient, attemptRepository, gate(),
                    corridor());
        }

        @Test
        @DisplayName("failover→corridor: per-txn max exceeded → declined, NO scheme call, NO float moved")
        void failover_perTxnMaxExceeded() {
            limitsFor("GMEREMIT", soaekHaeoemong());
            // 8.1M KRW ≈ 6,000 USD, over the 5,000 USD ceiling.
            assertThrows(TransactionLimitExceededException.class,
                    () -> failover().pay(FONEPAY_QR, OVER_CAP_KRW, "user-np",
                            "OVERSEAS", "KRW", WalletPartnerRef.GMEREMIT));

            assertNoSchemeCallAndNoFloatMoved();
            assertFailedAttemptPersisted();
        }

        @Test
        @DisplayName("failover→corridor: daily cap exceeded → declined, NO scheme call")
        void failover_dailyCapExceeded() {
            limitsFor("GMEREMIT", dailyCapOnly("10"));
            cumulativeBreach();

            assertThrows(CumulativeLimitExceededException.class,
                    () -> failover().pay(FONEPAY_QR, new BigDecimal("13500"), "user-np",
                            "OVERSEAS", "KRW", WalletPartnerRef.GMEREMIT));

            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("failover→corridor: within limits → APPROVED, cap charged on the real chargedUsd")
        void failover_withinLimitsApproves() {
            limitsFor("GMEREMIT", soaekHaeoemong());

            WalletResult result = failover().pay(FONEPAY_QR, new BigDecimal("13500"), "user-np",
                    "OVERSEAS", "KRW", WalletPartnerRef.GMEREMIT);

            assertTrue(result.approved(), "a within-limits Nepal payment must still approve");
            // (13,500 + 500 fee) / 1350 = 10.37037037 USD against the 50,000 USD annual cap — the
            // corridor's own fee-inclusive figure, not a converted pass-through amount.
            verify(prefundingClient).chargeCumulative(eq(1L), anyString(),
                    eq(new BigDecimal("10.37037037")),
                    eq(null), eq(null), eq(new BigDecimal("50000")), eq(null));
        }

        @Test
        @DisplayName("FAIL CLOSED: limits configured but the corridor cannot be priced → refused, NO scheme call")
        void failover_failsClosedWithoutPricing() {
            limitsFor("GMEREMIT", soaekHaeoemong());
            when(rateClient.fetchLiveRate("KRW", "NPR"))
                    .thenThrow(new PaymentException("rate provider down"));

            assertThrows(CorridorPricingUnavailableException.class,
                    () -> failover().pay(FONEPAY_QR, new BigDecimal("13500"), "user-np",
                            "OVERSEAS", "KRW", WalletPartnerRef.GMEREMIT));

            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("NepalPaymentService directly: per-txn max exceeded → declined, NO scheme call")
        void direct_perTxnMaxExceeded() {
            limitsFor("NEPAL", soaekHaeoemong());

            assertThrows(TransactionLimitExceededException.class,
                    () -> corridor().pay(FONEPAY_QR, OVER_CAP_KRW, "KRW", "user-np",
                            WalletPartnerRef.of("NEPAL", 7L)));

            assertNoSchemeCallAndNoFloatMoved();
        }

        @Test
        @DisplayName("NepalPaymentService directly: within limits → APPROVED")
        void direct_withinLimitsApproves() {
            limitsFor("NEPAL", soaekHaeoemong());

            WalletResult result = corridor().pay(FONEPAY_QR, new BigDecimal("13500"), "KRW", "user-np",
                    WalletPartnerRef.of("NEPAL", 7L));

            assertTrue(result.approved());
        }
    }
}
