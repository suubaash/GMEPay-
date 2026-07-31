package com.gme.pay.router.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads the <b>shipped</b> configuration and asserts that smart-router's outbound budgets nest inside
 * the budget of the caller waiting on them (gap <b>T3-11</b> defect 1).
 *
 * <h2>Why a config test and not a comment</h2>
 *
 * <p>The nesting rule is the whole reason the numbers are what they are: an inner leg must give up
 * <em>before</em> its caller does, so the caller learns "that hop failed" from an answer instead of
 * inferring it from its own socket timeout. Written down, that rule survives until the first person who
 * raises one value for one slow report. Asserted, it fails the build instead.
 *
 * <pre>
 *   smart-router -&gt; config-registry (resolve)   500ms   gmepay.config-registry.read-timeout-millis
 *   smart-router -&gt; anything else (floor)         5s    gmepay.http.client.read-timeout
 *   payment-executor -&gt; smart-router              5s    gmepay.http.client.read-timeout (its own)
 * </pre>
 *
 * <p>payment-executor's number is read out of <b>payment-executor's own properties file</b> rather than
 * restated here. The two are not independent knobs — smart-router's floor exists to keep a future
 * outbound client from outliving the payment that is waiting on the resolve — so tightening the caller
 * without re-checking this service must fail somewhere, and here is where.
 */
class OutboundBudgetNestingTest {

    @Test
    @DisplayName("the resolve-path budget is strictly tighter than this service's own fleet floor")
    void resolveBudgetNestsInsideTheServiceFloor() {
        Properties own = properties(repositoryRoot()
                .resolve("services/smart-router/src/main/resources/application.properties"));

        long resolveReadMillis = requiredLong(own, "gmepay.config-registry.read-timeout-millis");
        long resolveConnectMillis = requiredLong(own, "gmepay.config-registry.connect-timeout-millis");
        long floorReadMillis = durationMillis(required(own, "gmepay.http.client.read-timeout"));

        assertThat(resolveReadMillis)
                .as("the resolve read budget must be STRICTLY tighter than the service floor, so the "
                        + "hop with a declared degraded path is the one that gives up first")
                .isLessThan(floorReadMillis);
        assertThat(resolveConnectMillis)
                .as("a connect budget must be set too: without it a black-holed config-registry "
                        + "address waits out the kernel's SYN-retry budget, which no read timeout "
                        + "bounds")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("this service's fleet floor nests inside payment-executor's budget for calling it")
    void serviceFloorNestsInsideTheCallersBudget() {
        Path root = repositoryRoot();
        Properties own = properties(root
                .resolve("services/smart-router/src/main/resources/application.properties"));
        Properties caller = properties(root
                .resolve("services/payment-executor/src/main/resources/application.properties"));

        long floorReadMillis = durationMillis(required(own, "gmepay.http.client.read-timeout"));
        // payment-executor tightens the SAME fleet-floor property to 5s for its own internal hops,
        // smart-router among them; there is no separate per-hop key for the router leg.
        long callerReadMillis = durationMillis(required(caller, "gmepay.http.client.read-timeout"));

        assertThat(floorReadMillis)
                .as("smart-router must not permit an outbound call that outlives the payment waiting "
                        + "on it: payment-executor abandons the resolve after %dms, so anything this "
                        + "service is still waiting for past that point is work nobody will read",
                        callerReadMillis)
                .isLessThanOrEqualTo(callerReadMillis);
    }

    // ------------------------------------------------------------------------

    private static final Pattern DURATION =
            Pattern.compile("^(\\d+)(ms|s|m)?$", Pattern.CASE_INSENSITIVE);

    /** Spring's duration shorthand, restricted to the forms this repository actually ships. */
    private static long durationMillis(String value) {
        Matcher matcher = DURATION.matcher(value.trim());
        assertThat(matcher.matches())
                .as("unrecognised duration '%s' — extend this parser rather than loosening the test",
                        value)
                .isTrue();
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2) == null ? "ms" : matcher.group(2).toLowerCase();
        return switch (unit) {
            case "ms" -> amount;
            case "s" -> amount * 1_000L;
            default -> amount * 60_000L;
        };
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertThat(value)
                .as("%s must be stated in the shipped properties, not left to a @Value default that "
                        + "nobody reviews", key)
                .isNotNull();
        return value;
    }

    private static long requiredLong(Properties properties, String key) {
        return Long.parseLong(required(properties, key).trim());
    }

    private static Properties properties(Path file) {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        // Guard against a silently relocated file producing a vacuously passing test.
        assertThat(properties).as("%s is empty or missing", file).isNotEmpty();
        return properties;
    }

    /** Walks up from the module directory to the directory holding {@code settings.gradle}. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository root above "
                + Path.of("").toAbsolutePath());
    }
}
