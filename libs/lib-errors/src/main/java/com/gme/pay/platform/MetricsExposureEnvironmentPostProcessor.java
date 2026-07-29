package com.gme.pay.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Adds the Actuator <b>Prometheus scrape endpoint</b> to every GMEPay+ service's exposed endpoint
 * set, in one place (gap <b>T3-2</b>, COO audit finding #3).
 *
 * <h2>Why this exists</h2>
 * <p>Every service ships an explicit {@code management.endpoints.web.exposure.include} allow-list
 * (typically {@code health,info,metrics}). Boot's exposure filter is an allow-list: an endpoint that
 * is not named there returns 404 even when its bean exists. So making {@code /actuator/prometheus}
 * real needed two things — a Micrometer registry on the classpath (added once for all 20 deployables
 * in the root {@code build.gradle}) and {@code prometheus} in that include list. Doing the second
 * per-service would mean 19 near-identical one-line edits that a new service can silently forget,
 * which is exactly how T3-2 happened: a comment in {@code api-gateway/build.gradle} claimed the
 * endpoint and {@code SecurityConfig} even {@code permitAll}'d it, while it 404'd on every instance.
 *
 * <h2>Merge semantics — additive, never destructive</h2>
 * <p>This reads whatever the service resolved for
 * {@code management.endpoints.web.exposure.include} (its own {@code application.properties} /
 * {@code application.yml}, env vars and system properties are all already loaded at this point, see
 * "ordering" below) and re-contributes that same list <em>plus</em> {@code prometheus}. Nothing is
 * removed and no other management property is touched. If the list already contains
 * {@code prometheus} or the wildcard {@code *}, or the service explicitly <b>excludes</b>
 * {@code prometheus} via {@code management.endpoints.web.exposure.exclude}, this contributor does
 * nothing at all.
 *
 * <h2>Precedence and ordering</h2>
 * <p>Unlike {@link PlatformDefaultsEnvironmentPostProcessor} (which contributes *defaults* with
 * {@code addLast}, i.e. lowest precedence), this one must <b>win over</b> the service's own value —
 * that value is the thing being extended — so its property source is added with {@code addFirst}.
 * It is ordered {@link Ordered#LOWEST_PRECEDENCE} so it runs <em>after</em> Boot's
 * {@code ConfigDataEnvironmentPostProcessor}; otherwise the service's own include list would not yet
 * be loaded and there would be nothing to merge with.
 *
 * <h2>Escape hatch</h2>
 * <p>{@code gmepay.metrics.prometheus.expose=false} (env var
 * {@code GMEPAY_METRICS_PROMETHEUS_EXPOSE=false}) disables the contributor entirely, for an operator
 * who wants to lock an instance's management surface down to exactly what its own config names.
 *
 * <h2>What this does NOT do</h2>
 * <p>It does not authenticate the endpoint. Scrape authorisation stays where every other
 * introspection surface's authorisation lives — the per-service internal-auth gate
 * ({@code gmepay.internal-auth.path-patterns} / a service's own filter config), which lists
 * {@code /actuator/prometheus} next to {@code /actuator/metrics/**}. See
 * {@code Documentation/RUNBOOK_MONITORING.md} for the per-service matrix.
 */
public class MetricsExposureEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name of the property source this contributor adds. */
    public static final String PROPERTY_SOURCE_NAME = "gmepay-metrics-exposure";

    /** Set to {@code false} to leave the exposed endpoint set exactly as the service configured it. */
    public static final String ENABLED_KEY = "gmepay.metrics.prometheus.expose";

    /** The Actuator endpoint id added to the include list. */
    public static final String PROMETHEUS_ENDPOINT_ID = "prometheus";

    static final String INCLUDE_KEY = "management.endpoints.web.exposure.include";
    static final String EXCLUDE_KEY = "management.endpoints.web.exposure.exclude";

    /**
     * Boot's own default when a service names nothing. Kept explicit so a service without an include
     * list still keeps its container probes working after this contributor runs.
     */
    static final String BOOT_DEFAULT_INCLUDE = "health";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty(ENABLED_KEY, Boolean.class, Boolean.TRUE)) {
            return;
        }
        // Idempotent: post-processors can run more than once for one environment.
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        String merged = mergedInclude(
                environment.getProperty(INCLUDE_KEY),
                environment.getProperty(EXCLUDE_KEY));
        if (merged == null) {
            return; // already exposed, wildcarded, or deliberately excluded — leave it alone.
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(INCLUDE_KEY, merged);
        // addFirst => wins over the service's own include list, which is the value being extended.
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }

    /**
     * Computes the include list with {@code prometheus} appended, or {@code null} when no change
     * should be made.
     *
     * @param include the service's resolved include list ({@code null}/blank = Boot's default)
     * @param exclude the service's resolved exclude list, honoured as an explicit veto
     * @return the merged list, or {@code null} to leave the environment untouched
     */
    static String mergedInclude(String include, String exclude) {
        if (contains(exclude, PROMETHEUS_ENDPOINT_ID) || contains(exclude, "*")) {
            return null; // the service explicitly does not want it — respect that.
        }
        String base = (include == null || include.isBlank()) ? BOOT_DEFAULT_INCLUDE : include;
        if (contains(base, PROMETHEUS_ENDPOINT_ID) || contains(base, "*")) {
            return null; // already there.
        }
        List<String> ids = new ArrayList<>();
        for (String raw : base.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        ids.add(PROMETHEUS_ENDPOINT_ID);
        return String.join(",", ids);
    }

    private static boolean contains(String csv, String id) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .anyMatch(id::equals);
    }

    /**
     * Runs after Boot's {@code ConfigDataEnvironmentPostProcessor} so the service's own
     * {@code application.properties} / {@code application.yml} include list is already resolvable
     * and can be merged rather than replaced.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
