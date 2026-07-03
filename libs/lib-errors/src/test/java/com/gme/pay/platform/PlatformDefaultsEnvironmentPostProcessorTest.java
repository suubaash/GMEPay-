package com.gme.pay.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Pure unit tests for the platform-defaults EnvironmentPostProcessor (no Spring context). */
class PlatformDefaultsEnvironmentPostProcessorTest {

    private final PlatformDefaultsEnvironmentPostProcessor processor =
            new PlatformDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("contributes the three platform defaults at lowest precedence")
    void contributesDefaults() {
        StandardEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(env.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("25s");
        // Exact MDC key from iteration 4 (CorrelationHeaders.MDC_KEY == "correlationId").
        assertThat(env.getProperty("logging.pattern.level")).isEqualTo("%5p [%X{correlationId:-}]");
        // Added as the LAST (lowest-precedence) property source.
        assertThat(env.getPropertySources().stream().reduce((first, second) -> second).orElseThrow().getName())
                .isEqualTo(PlatformDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    @DisplayName("a service's own higher-precedence value is NOT overridden")
    void doesNotClobberServiceValue() {
        StandardEnvironment env = new StandardEnvironment();
        // Simulate a service that already sets server.shutdown at higher precedence.
        env.getPropertySources().addFirst(new MapPropertySource(
                "service-config", Collections.singletonMap("server.shutdown", "immediate")));

        processor.postProcessEnvironment(env, null);

        // Resolve returns the service's value, not the default.
        assertThat(env.getProperty("server.shutdown")).isEqualTo("immediate");
        // The other defaults are still contributed.
        assertThat(env.getProperty("logging.pattern.level")).isEqualTo("%5p [%X{correlationId:-}]");
    }

    @Test
    @DisplayName("gmepay.platform-defaults.enabled=false disables the contribution entirely")
    void disabledByFlag() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(
                "service-config",
                Collections.singletonMap(PlatformDefaultsEnvironmentPostProcessor.ENABLED_KEY, "false")));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("server.shutdown")).isNull();
        assertThat(env.getProperty("logging.pattern.level")).isNull();
        assertThat(env.getPropertySources().contains(
                PlatformDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    }
}
