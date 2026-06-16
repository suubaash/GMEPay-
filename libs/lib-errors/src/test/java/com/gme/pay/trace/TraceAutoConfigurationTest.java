package com.gme.pay.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Verifies the auto-config wires beans only when gmepay.trace.enabled=true. */
class TraceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TraceAutoConfiguration.class));

    @Test
    @DisplayName("disabled by default → no trace beans")
    void offByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TraceReporter.class));
    }

    @Test
    @DisplayName("enabled → TraceReporter + outbound customizers present, named from spring.application.name")
    void enabledWiresBeans() {
        runner.withPropertyValues("gmepay.trace.enabled=true", "spring.application.name=demo-svc")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceReporter.class);
                    assertThat(ctx.getBean(TraceReporter.class).self()).isEqualTo("demo-svc");
                    assertThat(ctx).hasBean("traceRestClientCustomizer");
                    assertThat(ctx).hasBean("traceRestTemplateCustomizer");
                });
    }

    @Test
    @DisplayName("serviceName property overrides spring.application.name")
    void serviceNameOverride() {
        runner.withPropertyValues("gmepay.trace.enabled=true",
                        "spring.application.name=ignored", "gmepay.trace.service-name=custom-name")
                .run(ctx -> assertThat(ctx.getBean(TraceReporter.class).self()).isEqualTo("custom-name"));
    }
}
