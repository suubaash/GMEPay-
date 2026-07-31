package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PrefundingClient;
import com.gme.pay.payment.domain.client.QrClient;
import com.gme.pay.payment.domain.client.RateClient;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CFO#11 — SENDMN's commercial terms come from configuration, and doing so changed nothing.
 *
 * <p>The corridor used to price itself off {@code FEE_KRW = 500} and an FX margin whose only
 * "configuration" was a {@code @Value} default of {@code 0.02}, while {@code config-registry} owned
 * commercial terms for every partner. Two sources of truth, code silently winning. This suite pins both
 * halves of the fix:
 *
 * <ol>
 *   <li><b>The regression that matters</b> — with nothing configured anywhere, the corridor still
 *       charges 2% and ₩500, to the last digit. Externalising a price must not move it.</li>
 *   <li><b>The fix is real</b> — once terms exist in config-registry they take effect, and the
 *       code-default state is loudly readable rather than invisible while they do not.</li>
 * </ol>
 *
 * <p>The mid rate is 3.5 MNT/KRW and the USD/KRW rate is deliberately left unstubbed in most cases, so
 * the sanctioned 1350 fallback is the basis — exactly the shape {@code SendmnPaymentServiceTest} asserts.
 */
class SendmnPricingConfigTest {

    private QrClient qrClient;
    private RateClient rateClient;
    private PrefundingClient prefundingClient;
    private SchemeClient schemeClient;
    private ExecutionAttemptRepository attemptRepository;
    private TransactionClient transactionClient;
    private RevenueLedgerClient revenueLedgerClient;
    private PartnerConfigClient partnerConfigClient;

    private static final BigDecimal MID_RATE = new BigDecimal("3.5");
    private static final BigDecimal AMOUNT_KRW = new BigDecimal("10000");
    private static final long PARTNER_ID = 2L;
    private static final long SENDMN_SCHEME_ID = 9L;

    /** The config-registry partner code + fee-schedule key the corridor resolves its terms under. */
    private static final String PARTNER_CODE = "SENDMN";

    /** amountKrw / 1350, 8dp HALF_UP — the USD volume a tiered/bps fee schedule is charged on. */
    private static final BigDecimal AMOUNT_USD = new BigDecimal("7.40740741");

    @BeforeEach
    void setUp() {
        qrClient = mock(QrClient.class);
        rateClient = mock(RateClient.class);
        prefundingClient = mock(PrefundingClient.class);
        schemeClient = mock(SchemeClient.class);
        attemptRepository = mock(ExecutionAttemptRepository.class);
        transactionClient = mock(TransactionClient.class);
        revenueLedgerClient = mock(RevenueLedgerClient.class);
        partnerConfigClient = mock(PartnerConfigClient.class);

        when(qrClient.resolve(anyString())).thenReturn(
                new QrClient.MerchantView("M001", "MNT Merchant", "MNT", "sendmn", "RETAIL", true));
        when(rateClient.fetchLiveRate("KRW", "MNT")).thenReturn(
                new RateClient.LiveRate("KRW", "MNT", MID_RATE, Instant.now(), "sim"));
        when(prefundingClient.deduct(anyLong(), anyString(), any())).thenReturn(
                new PrefundingClient.DeductionResult(new BigDecimal("0.038"), new BigDecimal("100.000")));
        when(schemeClient.submitMpm(any())).thenReturn(
                new SchemeClient.MpmSubmitResponse("SMN_AUTH_1", "SMN_TXN_1", Instant.now()));
        when(transactionClient.createPending(any())).thenReturn(
                new TransactionClient.CreateResult("txn-sendmn-001", "pay-001", Instant.now()));

        // Nothing configured, the default state of every environment today.
        when(partnerConfigClient.resolveFxConfig(anyString())).thenReturn(Optional.empty());
        when(partnerConfigClient.resolveServiceFeeUsd(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    /** The corridor as production wires it: config-registry consulted, no module-config override. */
    private SendmnCorridorPricing productionPricing() {
        return new SendmnCorridorPricing(rateClient, partnerConfigClient);
    }

    private SendmnPaymentService service(SendmnCorridorPricing pricing) {
        return new SendmnPaymentService(qrClient, rateClient, prefundingClient, schemeClient,
                attemptRepository, /* lenient */ false, pricing,
                transactionClient, revenueLedgerClient, null, null);
    }

    private WalletResult pay(SendmnCorridorPricing pricing) {
        return service(pricing).pay("ZPQR_MNT", AMOUNT_KRW, "user-mn-cfo11", PARTNER_ID);
    }

    private BigDecimal submittedMnt() {
        ArgumentCaptor<SchemeClient.MpmSubmitRequest> captor =
                ArgumentCaptor.forClass(SchemeClient.MpmSubmitRequest.class);
        verify(schemeClient).submitMpm(captor.capture());
        return captor.getValue().payoutAmount();
    }

    // =========================================================================================
    // 1. THE REGRESSION PIN. Absent configuration must preserve today's pricing exactly.
    // =========================================================================================

    @Test
    @DisplayName("nothing configured → today's EXACT pricing survives: ₩500 fee, 2% margin, 34300 MNT")
    void noConfiguredTerms_preservesTodaysExactPricing() {
        WalletResult result = pay(productionPricing());

        assertTrue(result.approved(), "an unconfigured corridor must keep transacting, not refuse");

        // The fee: still the flat ₩500, and still additive on top of the wallet amount.
        assertEquals(new BigDecimal("500"), result.feeKrw());
        assertEquals(new BigDecimal("10500"), result.chargedKrw());

        // The margin: still 2%, so still 3.5 × 0.98 = 3.43 MNT/KRW → 34,300 MNT on the wire.
        assertEquals(new BigDecimal("34300"), result.payAmountMnt());
        assertEquals(0, new BigDecimal("34300").compareTo(submittedMnt()));
        assertEquals(0, new BigDecimal("3.430000").compareTo(result.fxRate()));

        // The float debit: chargedKrw / 1350 at 8dp — the pre-existing basis, unchanged.
        ArgumentCaptor<BigDecimal> usd = ArgumentCaptor.forClass(BigDecimal.class);
        verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), usd.capture());
        assertEquals(0, new BigDecimal("7.77777778").compareTo(usd.getValue()));

        // The revenue booking: ₩500 service charge + the 2% margin as USD (200.00 / 1350 = 0.1481).
        verify(revenueLedgerClient).postRevenueCapture(
                eq("txn-sendmn-001"), eq(PARTNER_ID), eq(SENDMN_SCHEME_ID), any(),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.1481")),
                eq(new BigDecimal("500")), eq("KRW"), eq(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("the unconfigured state is VISIBLE: both terms report CODE_DEFAULT + owner action")
    void noConfiguredTerms_stateIsVisible() {
        SendmnCorridorPricing pricing = productionPricing();
        pay(pricing);

        CorridorPricing.Provenance p = pricing.provenance();
        assertEquals(SendmnCorridorPricing.CORRIDOR, p.corridor());
        assertTrue(p.marginOnCodeDefault(), "the 2% is a code default, and must say so");
        assertTrue(p.feeOnCodeDefault(), "the ₩500 is a code default, and must say so");
        assertTrue(p.marginSource().startsWith(CorridorPricing.CODE_DEFAULT), p.marginSource());
        assertTrue(p.feeSource().startsWith(CorridorPricing.CODE_DEFAULT), p.feeSource());
        // The provenance must name the config key an owner can reach for, not just say "default".
        assertTrue(p.marginSource().contains("gmepay.payment.sendmn.fx-margin"), p.marginSource());
        assertTrue(p.feeSource().contains("gmepay.payment.sendmn.service-fee-krw"), p.feeSource());
        assertTrue(p.ownerActionRequired(),
                "an owner must still confirm these values in config-registry");
    }

    // =========================================================================================
    // 2. Configured terms are actually used — the whole point of CFO#11.
    // =========================================================================================

    @Test
    @DisplayName("config-registry partner_fx_config margin (250 bps) prices the payment, not the 2%")
    void configRegistryMarginIsUsed() {
        when(partnerConfigClient.resolveFxConfig(PARTNER_CODE)).thenReturn(Optional.of(
                new PartnerConfigClient.FxTerms(new BigDecimal("250"), "MID_MARKET")));

        SendmnCorridorPricing pricing = productionPricing();
        WalletResult result = pay(pricing);

        assertTrue(result.approved());
        // offer = 3.5 × (1 − 0.025) = 3.4125 → 10,000 × 3.4125 = 34,125 MNT (NOT 34,300)
        assertEquals(0, new BigDecimal("34125").compareTo(result.payAmountMnt()));
        assertEquals(0, new BigDecimal("34125").compareTo(submittedMnt()));
        // and the booked margin follows the configured rate: 10,000 × 0.025 = ₩250 / 1350 = 0.1852 USD
        verify(revenueLedgerClient).postRevenueCapture(
                anyString(), anyLong(), anyLong(), any(),
                eq(BigDecimal.ZERO), eq(new BigDecimal("0.1852")),
                any(), eq("KRW"), any());

        assertFalse(pricing.provenance().marginOnCodeDefault(),
                "a configured margin must not report as a code default");
        assertEquals("config-registry partner_fx_config", pricing.provenance().marginSource());
    }

    @Test
    @DisplayName("config-registry partner_fee_schedule fee (0.5 USD) is charged, not the ₩500")
    void configRegistryFeeIsUsed() {
        when(partnerConfigClient.resolveServiceFeeUsd(PARTNER_CODE, "SENDMN", "OVERSEAS", AMOUNT_USD))
                .thenReturn(Optional.of(new BigDecimal("0.5")));

        SendmnCorridorPricing pricing = productionPricing();
        WalletResult result = pay(pricing);

        assertTrue(result.approved());
        // 0.5 USD × 1350 = ₩675 — the configured fee, replacing the hardcoded ₩500.
        assertEquals(0, new BigDecimal("675").compareTo(result.feeKrw()));
        assertEquals(0, new BigDecimal("10675").compareTo(result.chargedKrw()));
        verify(revenueLedgerClient).postRevenueCapture(
                anyString(), anyLong(), anyLong(), any(), any(), any(),
                eq(new BigDecimal("675")), eq("KRW"), any());

        assertFalse(pricing.provenance().feeOnCodeDefault());
        assertTrue(pricing.provenance().ownerActionRequired(),
                "the fee is configured but the MARGIN is not — a partially-configured corridor must "
                        + "still ask for the owner's attention");
        assertTrue(pricing.provenance().marginOnCodeDefault());
    }

    @Test
    @DisplayName("both terms configured → no owner action outstanding")
    void bothConfigured_noOwnerActionOutstanding() {
        when(partnerConfigClient.resolveFxConfig(PARTNER_CODE)).thenReturn(Optional.of(
                new PartnerConfigClient.FxTerms(new BigDecimal("250"), "SEOUL_FX_BROKER")));
        when(partnerConfigClient.resolveServiceFeeUsd(PARTNER_CODE, "SENDMN", "OVERSEAS", AMOUNT_USD))
                .thenReturn(Optional.of(new BigDecimal("0.5")));

        SendmnCorridorPricing pricing = productionPricing();
        assertTrue(pay(pricing).approved());

        assertFalse(pricing.provenance().ownerActionRequired());
        assertFalse(pricing.provenance().marginOnCodeDefault());
        assertFalse(pricing.provenance().feeOnCodeDefault());
    }

    @Test
    @DisplayName("module-config overrides beat config-registry — the local/sim escape hatch still works")
    void moduleConfigOverridesWin() {
        when(partnerConfigClient.resolveFxConfig(PARTNER_CODE)).thenReturn(Optional.of(
                new PartnerConfigClient.FxTerms(new BigDecimal("250"), "MID_MARKET")));
        when(partnerConfigClient.resolveServiceFeeUsd(anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(new BigDecimal("0.5")));

        SendmnCorridorPricing pricing =
                new SendmnCorridorPricing(rateClient, partnerConfigClient, "0.01", "300");
        WalletResult result = pay(pricing);

        // 1% margin: 3.5 × 0.99 = 3.465 → 34,650 MNT; ₩300 fee.
        assertEquals(0, new BigDecimal("34650").compareTo(result.payAmountMnt()));
        assertEquals(0, new BigDecimal("300").compareTo(result.feeKrw()));
        assertEquals("gmepay.payment.sendmn.fx-margin", pricing.provenance().marginSource());
        assertEquals("gmepay.payment.sendmn.service-fee-krw", pricing.provenance().feeSource());
        assertFalse(pricing.provenance().ownerActionRequired());
    }

    // =========================================================================================
    // 3. The USD/KRW basis. A deliberate, configurable decision — NOT a price.
    // =========================================================================================

    @Nested
    @DisplayName("USD/KRW basis: falls back by default, fails closed only when an owner asks")
    class UsdBasis {

        @Test
        @DisplayName("default: an unavailable live USD/KRW still falls back to 1350 — no outage")
        void defaultFallsBack() {
            when(rateClient.fetchLiveRate("USD", "KRW"))
                    .thenThrow(new PaymentException("rate provider down"));

            SendmnCorridorPricing pricing = productionPricing();
            WalletResult result = pay(pricing);

            assertTrue(result.approved(),
                    "a rate blip must not take a live corridor offline — the basis is a control, "
                            + "not a price, and the deduction stays conservative");
            ArgumentCaptor<BigDecimal> usd = ArgumentCaptor.forClass(BigDecimal.class);
            verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), usd.capture());
            assertEquals(0, new BigDecimal("7.77777778").compareTo(usd.getValue()));
            assertTrue(pricing.provenance().usdBasisFallbackSeen(),
                    "the fallback must be reported, so T2-2's rate-basis variance has a witness");
        }

        @Test
        @DisplayName("live USD/KRW present → the live rate is the basis and no fallback is reported")
        void liveRateReported() {
            when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(
                    new RateClient.LiveRate("USD", "KRW", new BigDecimal("1380"), Instant.now(), "sim"));

            SendmnCorridorPricing pricing = productionPricing();
            assertTrue(pay(pricing).approved());

            ArgumentCaptor<BigDecimal> usd = ArgumentCaptor.forClass(BigDecimal.class);
            verify(prefundingClient).deduct(eq(PARTNER_ID), anyString(), usd.capture());
            assertEquals(0, new BigDecimal("7.60869565").compareTo(usd.getValue()));
            assertFalse(pricing.provenance().usdBasisFallbackSeen());
        }

        @Test
        @DisplayName("usd-basis-strict=true: an unavailable USD/KRW refuses, no float moved, no scheme call")
        void strictModeFailsClosed() {
            when(rateClient.fetchLiveRate("USD", "KRW"))
                    .thenThrow(new PaymentException("rate provider down"));

            SendmnCorridorPricing strict = new SendmnCorridorPricing(
                    rateClient, partnerConfigClient, "", "", /* usdBasisStrict */ true);

            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(strict));

            assertEquals(CorridorPricingUnavailableException.CODE_RATE_UNAVAILABLE, ex.code());
            assertTrue(ex.retryable(), "a rate-provider outage is transient");
            verify(prefundingClient, never()).deduct(anyLong(), anyString(), any());
            verify(schemeClient, never()).submitMpm(any());
        }

        @Test
        @DisplayName("an unavailable KRW/MNT rate refuses in BOTH modes — an offer rate is never guessed")
        void offerRateNeverFallsBack() {
            when(rateClient.fetchLiveRate("KRW", "MNT"))
                    .thenThrow(new PaymentException("rate provider down"));

            CorridorPricingUnavailableException ex = assertThrows(
                    CorridorPricingUnavailableException.class, () -> pay(productionPricing()));

            assertEquals(CorridorPricingUnavailableException.CODE_RATE_UNAVAILABLE, ex.code());
            verify(prefundingClient, never()).deduct(anyLong(), anyString(), any());
            verify(schemeClient, never()).submitMpm(any());
        }
    }

    // =========================================================================================
    // 4. The two corridors keep their DIFFERENT unconfigured behaviour, on purpose.
    // =========================================================================================

    @Test
    @DisplayName("Nepal still fails closed unconfigured while SENDMN still transacts — deliberate, not drift")
    void nepalStillFailsClosedWhileSendmnDoesNot() {
        // Same resolver, same absent configuration, opposite outcome — because Nepal declares no code
        // default (it had no working price to preserve) and SENDMN does (it has live traffic).
        NepalCorridorPricing nepal = new NepalCorridorPricing(rateClient, partnerConfigClient);
        when(rateClient.fetchLiveRate("KRW", "NPR")).thenReturn(
                new RateClient.LiveRate("KRW", "NPR", new BigDecimal("0.10"), Instant.now(), "sim"));
        when(rateClient.fetchLiveRate("USD", "KRW")).thenReturn(
                new RateClient.LiveRate("USD", "KRW", new BigDecimal("1350"), Instant.now(), "sim"));

        CorridorPricingUnavailableException ex = assertThrows(
                CorridorPricingUnavailableException.class, () -> nepal.resolveRates("GMEREMIT"));
        assertEquals(CorridorPricingUnavailableException.CODE_NOT_CONFIGURED, ex.code());

        assertTrue(pay(productionPricing()).approved(),
                "SENDMN must NOT inherit Nepal's fail-closed policy — that would be an outage");
    }
}
