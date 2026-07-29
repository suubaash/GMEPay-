package com.gme.pay.payment.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;

/**
 * <b>T3-3 default-ON proof</b> for the DECLINE_SPIKE monitor.
 *
 * <p>The gap was not that the monitor was missing — it was well built — but that it was
 * {@code @ConditionalOnProperty(havingValue = "true")} with no {@code matchIfMissing}, so the bean
 * simply did not exist unless a deployment remembered one environment variable, and none did. Together
 * with transaction-mgmt's equally default-off stuck-transaction sweeper, that left the money path with
 * zero active safety nets: a 100% decline spike at 3am paged nobody.
 *
 * <p>Asserted from two independent directions so the regression cannot come back through either door,
 * mirroring how auth-identity pinned its internal-auth gate
 * ({@code InternalAuthEnforcedConfigTest#shippedConfigDoesNotDefaultTheGateOff}):
 *
 * <ol>
 *   <li>the <b>annotation</b> on the class — {@code matchIfMissing = true}, so an absent property
 *       means ON, not OFF;</li>
 *   <li>the <b>shipped {@code application.properties}</b> — the packaged artifact must not ship
 *       {@code gmepay.decline-spike.enabled=false}, and must not reintroduce the env-defeatable
 *       {@code ${GMEPAY_DECLINE_SPIKE_ENABLED:false}} form.</li>
 * </ol>
 *
 * <p>It also pins the thresholds as configurable-with-sane-defaults, because "on by default" is only
 * useful if the firing conditions are both sensible and tunable without a code change.
 */
class DeclineSpikeMonitorDefaultOnTest {

    private static final String ENABLED_KEY = "gmepay.decline-spike.enabled";

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

    @Test
    @DisplayName("the bean condition treats an ABSENT property as enabled (matchIfMissing = true)")
    void conditionMatchesWhenPropertyIsMissing() {
        ConditionalOnProperty condition =
                DeclineSpikeMonitor.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).as("the monitor must stay config-gated, not unconditional").isNotNull();
        assertThat(condition.name()).containsExactly(ENABLED_KEY);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing())
                .as("a deployment that sets nothing must still get the decline safety net")
                .isTrue();
    }

    @Test
    @DisplayName("the SHIPPED application.properties enables the monitor and is not env-defeatable")
    void shippedConfigEnablesTheMonitor() throws Exception {
        // Reads the packaged config, so re-introducing the default-off value — the exact T3-3
        // regression — fails here even if every other test still passes.
        assertThat(shippedConfig().getProperty(ENABLED_KEY))
                .as("%s must ship as true", ENABLED_KEY)
                .isEqualTo("true");

        assertThat(shippedConfigWithoutComments())
                .as("the flag must not be defeatable by forgetting an env var")
                .doesNotContain("GMEPAY_DECLINE_SPIKE_ENABLED")
                .doesNotContain(ENABLED_KEY + "=false");
    }

    @Test
    @DisplayName("thresholds are shipped, sane, and overridable from config")
    void thresholdsAreSaneAndConfigurable() throws Exception {
        Properties props = shippedConfig();

        long window = Long.parseLong(props.getProperty("gmepay.decline-spike.window-seconds"));
        int minSamples = Integer.parseInt(props.getProperty("gmepay.decline-spike.min-samples"));
        double rate = Double.parseDouble(props.getProperty("gmepay.decline-spike.threshold-rate"));
        long cooldown = Long.parseLong(props.getProperty("gmepay.decline-spike.cooldown-seconds"));

        // Window long enough to accumulate a statistically meaningful sample, short enough to detect
        // an outage inside an SLA response window.
        assertThat(window).isBetween(30L, 300L);
        // Enough samples that one early decline cannot trip a "100% decline rate".
        assertThat(minSamples).isGreaterThanOrEqualTo(10);
        // A majority-decline threshold: strictly a spike, not normal fraud/limit declines.
        assertThat(rate).isBetween(0.2, 0.9);
        // Non-zero cooldown, so one outage does not flood the notification sink...
        assertThat(cooldown).isPositive();
        // ...but short enough that an ongoing outage re-pages within the hour.
        assertThat(cooldown).isLessThanOrEqualTo(3600L);
    }

    @Test
    @DisplayName("the durable archive + retention are enabled in the shipped config too")
    void durabilityIsOnByDefault() throws Exception {
        Properties props = shippedConfig();

        assertThat(props.getProperty("gmepay.ops.alerts.prune-enabled")).isEqualTo("true");
        assertThat(Integer.parseInt(props.getProperty("gmepay.ops.alerts.retention-days")))
                .as("alerts must outlive an incident review")
                .isGreaterThanOrEqualTo(30);
    }

    @Test
    @DisplayName("NO webhook-url ships — a blank URL would activate a sink that fails every delivery")
    void noBlankWebhookUrlIsShipped() throws Exception {
        // @ConditionalOnProperty matches an EMPTY value, so `gmepay.alert.sink.webhook-url=` would
        // register WebhookAlertSink with no target and silently fail every page. The log fallback must
        // win until an operator supplies a real URL.
        assertThat(shippedConfig().getProperty("gmepay.alert.sink.webhook-url")).isNull();
    }
}
