package com.gme.pay.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Pure unit tests (no Spring context) for the T3-2 metrics-exposure contributor.
 *
 * <p>The regression this pins: {@code /actuator/prometheus} 404'd on all 19 services because every
 * service's {@code management.endpoints.web.exposure.include} allow-list named
 * {@code health,info,metrics} and nothing added {@code prometheus}. The contributor must ADD to that
 * list (never replace it — dropping {@code health} would break every container probe) and must lose
 * to an explicit exclude.
 */
class MetricsExposureEnvironmentPostProcessorTest {

    private final MetricsExposureEnvironmentPostProcessor processor =
            new MetricsExposureEnvironmentPostProcessor();

    private static StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("service-config", props));
        return env;
    }

    @Test
    @DisplayName("appends prometheus to the fleet-standard include list without dropping health")
    void appendsToFleetStandardList() {
        StandardEnvironment env = envWith(Collections.singletonMap(
                MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY, "health,info,metrics"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY))
                .isEqualTo("health,info,metrics,prometheus");
    }

    @Test
    @DisplayName("wins over the service's own include list (addFirst, not addLast)")
    void takesPrecedenceOverServiceConfig() {
        StandardEnvironment env = envWith(Collections.singletonMap(
                MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY, "health,info,metrics"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().iterator().next().getName())
                .isEqualTo(MetricsExposureEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    @DisplayName("no include list configured → keeps Boot's health default and adds prometheus")
    void handlesAbsentIncludeList() {
        StandardEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY))
                .isEqualTo("health,prometheus");
    }

    @Test
    @DisplayName("already exposed (explicitly or via wildcard) → contributes nothing")
    void noOpWhenAlreadyExposed() {
        assertThat(MetricsExposureEnvironmentPostProcessor
                .mergedInclude("health,prometheus", null)).isNull();
        assertThat(MetricsExposureEnvironmentPostProcessor
                .mergedInclude("health, PROMETHEUS ", null)).isNull();
        assertThat(MetricsExposureEnvironmentPostProcessor.mergedInclude("*", null)).isNull();
    }

    @Test
    @DisplayName("an explicit exclude vetoes the contributor")
    void explicitExcludeWins() {
        assertThat(MetricsExposureEnvironmentPostProcessor
                .mergedInclude("health,info,metrics", "prometheus")).isNull();
        assertThat(MetricsExposureEnvironmentPostProcessor
                .mergedInclude("health,info,metrics", "*")).isNull();
    }

    @Test
    @DisplayName("gmepay.metrics.prometheus.expose=false → environment untouched")
    void escapeHatchDisablesContributor() {
        StandardEnvironment env = envWith(Map.of(
                MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY, "health,info,metrics",
                MetricsExposureEnvironmentPostProcessor.ENABLED_KEY, "false"));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY))
                .isEqualTo("health,info,metrics");
        assertThat(env.getPropertySources()
                .contains(MetricsExposureEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }

    @Test
    @DisplayName("running twice adds one property source (idempotent)")
    void idempotent() {
        StandardEnvironment env = envWith(Collections.singletonMap(
                MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY, "health,info,metrics"));

        processor.postProcessEnvironment(env, null);
        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(MetricsExposureEnvironmentPostProcessor.INCLUDE_KEY))
                .isEqualTo("health,info,metrics,prometheus");
        long sources = env.getPropertySources().stream()
                .filter(s -> MetricsExposureEnvironmentPostProcessor.PROPERTY_SOURCE_NAME
                        .equals(s.getName()))
                .count();
        assertThat(sources).isEqualTo(1);
    }

    @Test
    @DisplayName("registered in spring.factories so every service inherits it")
    void registeredInSpringFactories() throws Exception {
        String factories;
        try (var in = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(in).as("META-INF/spring.factories must be on the lib-errors classpath")
                    .isNotNull();
            factories = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(factories)
                .as("un-registering the contributor would silently restore the T3-2 404")
                .contains(MetricsExposureEnvironmentPostProcessor.class.getName());
    }
}
