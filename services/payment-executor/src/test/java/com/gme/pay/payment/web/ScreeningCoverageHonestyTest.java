package com.gme.pay.payment.web;

import com.gme.pay.kyb.NoProviderPaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningProvenance;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.payment.domain.PaymentScreeningGate;
import com.gme.pay.payment.persistence.UnscreenedPaymentCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T5-3 task 4: <b>nothing may report AML coverage that does not exist.</b>
 *
 * <p>The specific failure this guards against is the one the CISO audit found elsewhere on this platform
 * and that T5-2 / T5-8 had to unwind: a surface that renders a green, zero-problems reading of a control
 * that was merely <i>configured</i> — or in this case, of a control that is absent entirely. A coverage
 * endpoint is unusually dangerous that way, because "0 unscreened payments" is the literal truth on a
 * platform that has never screened anything and has taken no traffic, and it is also exactly what
 * perfect compliance looks like.
 *
 * <p>So the invariant asserted here is: <b>a zero can never be returned without the posture that
 * disambiguates it</b>, and the no-provider case must say so in words a dashboard cannot silently drop.
 */
class ScreeningCoverageHonestyTest {

    private static final Instant TUE = Instant.parse("2026-07-28T03:00:00Z");

    private static ScreeningCoverageController controller(PaymentScreeningPort port,
                                                          long total,
                                                          UnscreenedPaymentCounter counter) {
        when(counter.recent(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(counter.total(any())).thenReturn(total);
        return new ScreeningCoverageController(counter,
                new PaymentScreeningGate(port, null, null, null, false,
                        Clock.fixed(TUE, ZoneOffset.UTC)));
    }

    private static PaymentScreeningPort realProvider() {
        return new PaymentScreeningPort() {
            @Override
            public ScreeningResult screen(PaymentScreeningSubject subject) {
                return new ScreeningResult(ScreeningResult.Status.CLEAR, List.of(), TUE, "r",
                        ScreeningProvenance.vendor("acme"));
            }

            @Override
            public String providerId() {
                return "acme";
            }

            @Override
            public boolean authoritative() {
                return true;
            }
        };
    }

    @Test
    @DisplayName("no provider + zero counted: the response REFUSES to read as coverage")
    void zeroWithNoProvider_isNotPresentedAsCoverage() {
        var response = controller(new NoProviderPaymentScreeningPort(), 0L,
                mock(UnscreenedPaymentCounter.class)).coverage(null, null, 50);

        // The posture fields must make the zero unreadable as success...
        assertFalse(response.screeningActive(), "no provider is wired");
        assertTrue("none".equals(response.providerId()));
        // ...and the interpretation must say it in words, because a consumer that renders only the number
        // is the consumer this test exists for.
        String text = response.interpretation();
        assertTrue(text.contains("NO SCREENING IS CONFIGURED"), text);
        assertTrue(text.contains("NOTHING WAS COUNTED"), text);
        assertTrue(text.contains("does NOT mean payments were screened")
                        || text.contains("not mean payments were screened"), text);

        // And it must never use the vocabulary of a passed control.
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("compliant"), text);
        assertFalse(lower.contains("all clear"), text);
        assertFalse(lower.contains("passed"), text);
    }

    @Test
    @DisplayName("a real provider with zero counted MAY read as coverage — the honest positive case")
    void zeroWithARealProvider_isAllowedToReadAsCoverage() {
        var response = controller(realProvider(), 0L, mock(UnscreenedPaymentCounter.class))
                .coverage(null, null, 50);

        assertTrue(response.screeningActive());
        assertTrue(response.interpretation().contains("authoritative screening provider is configured"),
                response.interpretation());
    }

    @Test
    @DisplayName("a real provider WITH unscreened parties still reports the shortfall, not the provider")
    void nonZeroWithARealProvider_reportsTheShortfall() {
        var response = controller(realProvider(), 42L, mock(UnscreenedPaymentCounter.class))
                .coverage(null, null, 50);

        // Having bought a vendor is not the same as having coverage: NO_SUBJECT_IDENTITY payments still
        // count against you, and the endpoint says the number out loud.
        assertTrue(response.interpretation().contains("42"), response.interpretation());
        assertTrue(response.interpretation().contains("went unscreened"), response.interpretation());
    }

    @Test
    @DisplayName("payment-executor's config states plainly that no AML control exists")
    void serviceConfigDoesNotClaimAmlCoverage() throws Exception {
        String props = new String(getClass().getClassLoader()
                .getResourceAsStream("application.properties").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        // The properties file is where an operator looks to learn what this service enforces. It must not
        // leave `gmepay.screening.fail-closed=false` sitting there looking like a tuning knob on a
        // working control.
        assertTrue(props.contains("gmepay.screening.fail-closed=false"),
                "fail-closed must be OFF by default — refusing every live payment is a business call");
        assertTrue(props.contains("NO PROVIDER IS CONFIGURED"), props.length() + " chars");
        assertTrue(props.contains("They screen nobody"),
                "the config must disambiguate the limit gates from screening");
    }
}
