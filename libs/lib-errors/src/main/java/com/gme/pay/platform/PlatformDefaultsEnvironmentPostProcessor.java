package com.gme.pay.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Contributes platform-wide maturity <em>defaults</em> that every GMEPay+ service inherits without
 * any per-service configuration edit, by adding a single lowest-precedence property source to the
 * environment before the application context is built.
 *
 * <p>Two behaviors are enabled by these defaults:
 *
 * <ol>
 *   <li><b>Graceful shutdown / connection draining.</b> {@code server.shutdown=graceful} plus a
 *       {@code spring.lifecycle.timeout-per-shutdown-phase}. Combined with the Actuator readiness
 *       probe present since iteration 3, Spring Boot flips readiness to {@code REFUSING_TRAFFIC} on
 *       SIGTERM and drains in-flight requests before exiting — enabling zero-downtime deploys.</li>
 *   <li><b>Correlation id in the log pattern.</b> {@code logging.pattern.level} is set to include the
 *       {@link com.gme.pay.correlation.CorrelationHeaders#MDC_KEY correlationId} MDC value written by
 *       the iteration-4 {@code CorrelationIdFilter}. The {@code :-} default renders an empty string on
 *       non-request threads so background logging stays clean.</li>
 * </ol>
 *
 * <p><b>Precedence / blast radius.</b> The property source is added with
 * {@link org.springframework.core.env.MutablePropertySources#addLast(org.springframework.core.env.PropertySource)
 * addLast}, i.e. LOWEST precedence. Anything a service actually sets — {@code application.properties}
 * / {@code application.yml}, OS environment variables, JVM system properties, and command-line args —
 * all sit higher and therefore override these defaults. A service that already sets, say,
 * {@code server.shutdown=immediate} keeps its own value; this contributor never clobbers it.
 *
 * <p><b>Escape hatch.</b> If {@code gmepay.platform-defaults.enabled=false} is already resolvable from
 * the environment (e.g. via env var or system property before the context starts), the contributor
 * does nothing.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Name of the property source this contributor adds; also the disable toggle key. */
    public static final String PROPERTY_SOURCE_NAME = "gmepay-platform-defaults";

    /** Set to {@code false} to skip contributing any platform defaults. */
    public static final String ENABLED_KEY = "gmepay.platform-defaults.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Escape hatch: honour an explicit opt-out that is already resolvable at this early stage.
        if (!environment.getProperty(ENABLED_KEY, Boolean.class, Boolean.TRUE)) {
            return;
        }
        // Idempotent: never add the source twice (post-processors can run more than once).
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        // (1) Graceful shutdown / connection draining for zero-downtime deploys.
        defaults.put("server.shutdown", "graceful");
        defaults.put("spring.lifecycle.timeout-per-shutdown-phase", "25s");
        // (2) Surface the iteration-4 correlation id (MDC key "correlationId") in the log level pattern.
        //     ":-" renders empty when the key is absent so non-request threads log cleanly.
        defaults.put("logging.pattern.level", "%5p [%X{correlationId:-}]");

        // addLast => LOWEST precedence: any service-provided config overrides these defaults.
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
