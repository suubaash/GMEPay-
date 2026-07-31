package com.gme.pay.payment.dayclose;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.payment.replay.RevenuePostingReplayScheduler;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;

/**
 * <b>T2-5 default-ON pin</b> for the three ledger-ops schedulers, and the record of WHY that is the choice.
 *
 * <p>Gap T2-5 asked explicitly which way these default and for the reasoning to be stated. The answer is ON,
 * matching this service's own T3-3 ops convention — {@code DeclineSpikeMonitor} and
 * {@code OpsAlertRetentionSweeper} are both {@code matchIfMissing = true}, for the reason T3-3 spelled out: a
 * safety net that has to be switched on is a safety net that is off in production. All three of these jobs are in
 * that class:
 *
 * <ul>
 *   <li>the <b>replay</b> is the gap — with it off, {@code revenue_posting_failures} accumulates and booked
 *       revenue stays missing from the ledger;</li>
 *   <li>the <b>day-close</b> and <b>FX exposure</b> runs are strictly READ-ONLY over three services (plus one
 *       upsert into payment-executor's own table), so an unattended run costs a row, not money.</li>
 * </ul>
 *
 * <p>The two divergences elsewhere in the fleet are deliberate and neither contradicts this:
 * {@code AuthorizationExpirySweeper} defaults OFF because it MOVES MONEY (it releases float holds), and
 * settlement-reconciliation's corridor recon defaults OFF because it writes into a shared ops exception queue and
 * pages people about corridors nobody has onboarded yet. Neither is true of a read-only report.
 *
 * <p>Asserted from two independent directions, mirroring {@code DeclineSpikeMonitorDefaultOnTest}: the annotation
 * on each class, and the SHIPPED {@code application.properties} — so the regression cannot come back through
 * either door.
 */
class LedgerOpsSchedulerDefaultOnTest {

    private static Properties shippedConfig() throws Exception {
        Properties props = new Properties();
        try (var in = new ClassPathResource("application.properties").getInputStream()) {
            props.load(in);
        }
        return props;
    }

    private static String shippedConfigWithoutComments() throws Exception {
        String raw;
        try (var in = new ClassPathResource("application.properties").getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return raw.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    private static void assertDefaultOn(Class<?> scheduler, String key) {
        ConditionalOnProperty condition = scheduler.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition)
                .as("%s must stay config-gated, not unconditional", scheduler.getSimpleName())
                .isNotNull();
        assertThat(condition.name()).containsExactly(key);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing())
                .as("a deployment that sets nothing must still get %s", scheduler.getSimpleName())
                .isTrue();
    }

    @Test
    @DisplayName("all three ledger-ops schedulers treat an ABSENT property as enabled")
    void allThreeSchedulersDefaultOn() {
        assertDefaultOn(RevenuePostingReplayScheduler.class, "gmepay.revenue-posting-replay.enabled");
        assertDefaultOn(DayCloseScheduler.class, "gmepay.day-close.enabled");
        assertDefaultOn(FxExposureScheduler.class, "gmepay.fx-exposure.enabled");
    }

    @Test
    @DisplayName("the SHIPPED application.properties enables all three and is not env-defeatable")
    void shippedConfigEnablesAllThree() throws Exception {
        Properties props = shippedConfig();
        assertThat(props.getProperty("gmepay.revenue-posting-replay.enabled")).isEqualTo("true");
        assertThat(props.getProperty("gmepay.day-close.enabled")).isEqualTo("true");
        assertThat(props.getProperty("gmepay.fx-exposure.enabled")).isEqualTo("true");

        assertThat(shippedConfigWithoutComments())
                .as("the flags must not be defeatable by forgetting an env var")
                .doesNotContain("GMEPAY_REVENUE_POSTING_REPLAY_ENABLED")
                .doesNotContain("GMEPAY_DAY_CLOSE_ENABLED")
                .doesNotContain("GMEPAY_FX_EXPOSURE_ENABLED")
                .doesNotContain("gmepay.revenue-posting-replay.enabled=false")
                .doesNotContain("gmepay.day-close.enabled=false")
                .doesNotContain("gmepay.fx-exposure.enabled=false");
    }

    @Test
    @DisplayName("the replay bound is finite and its backoff is sane — an unbounded retry never poisons")
    void replayBoundsAreSane() throws Exception {
        Properties props = shippedConfig();

        int maxAttempts = Integer.parseInt(props.getProperty("gmepay.revenue-posting-replay.max-attempts"));
        long base = Long.parseLong(props.getProperty("gmepay.revenue-posting-replay.base-backoff-seconds"));
        long max = Long.parseLong(props.getProperty("gmepay.revenue-posting-replay.max-backoff-seconds"));
        int batchSize = Integer.parseInt(props.getProperty("gmepay.revenue-posting-replay.batch-size"));

        // Finite, or nothing is ever escalated to a human.
        assertThat(maxAttempts).isBetween(3, 50);
        // Long enough that a restarting revenue-ledger is not hammered...
        assertThat(base).isGreaterThanOrEqualTo(10L);
        // ...capped so a long-lived failure still gets re-tried within a working day.
        assertThat(max).isBetween(base, 86_400L);
        // Bounded batch, so one sweep cannot become an unbounded burst of POSTs.
        assertThat(batchSize).isBetween(1, 1000);
    }

    @Test
    @DisplayName("no partner codes ship for the day-close float leg — a guessed code would be a fake tie-out")
    void noPartnerCodesAreShipped() throws Exception {
        // prefunding is keyed by partner CODE and transaction-mgmt carries none, so the codes cannot be derived.
        // Shipping a guess would tie the float out against the wrong partner and report a variance that is an
        // artefact of the guess; leaving it blank makes the leg honestly unavailable instead.
        assertThat(shippedConfigWithoutComments())
                .contains("gmepay.day-close.partner-codes=${GMEPAY_DAY_CLOSE_PARTNER_CODES:}");
    }

    @Test
    @DisplayName("the FX fallback-basis detector ships configured for KRW only")
    void fxFallbackDetectorIsConfigured() throws Exception {
        Properties props = shippedConfig();

        assertThat(props.getProperty("gmepay.fx-exposure.fallback-basis-currency")).isEqualTo("KRW");
        // Mirrors SendmnPaymentService.KRW_PER_USD. It is a DETECTOR of that constant's use, never a rate this
        // service prices anything at.
        assertThat(props.getProperty("gmepay.fx-exposure.fallback-basis-rate")).isEqualTo("1350");
        assertThat(Integer.parseInt(props.getProperty("gmepay.fx-exposure.window-days")))
                .as("exposure is cumulative, so the window must be more than a single day")
                .isGreaterThan(1);
    }
}
