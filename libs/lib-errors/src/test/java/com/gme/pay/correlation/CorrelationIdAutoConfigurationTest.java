package com.gme.pay.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Context-runner tests for {@link CorrelationIdAutoConfiguration}, pinning the two
 * classpath shapes that matter:
 *
 * <ul>
 *   <li>a servlet web app registers the {@code correlationIdFilter}; and</li>
 *   <li>a classpath WITHOUT {@code jakarta.servlet.Filter} (the api-gateway /
 *       WebFlux case, reproduced with {@link FilteredClassLoader}) must not blow up
 *       introspecting the auto-config — the regression that broke every
 *       {@code api-gateway} Spring context test — while still contributing the
 *       outbound-propagation interceptor bean.</li>
 * </ul>
 */
class CorrelationIdAutoConfigurationTest {

    @Test
    @DisplayName("servlet web app registers the correlationIdFilter")
    void servletApp_registersCorrelationFilter() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CorrelationIdAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("correlationIdFilter");
                    assertThat(ctx).hasSingleBean(CorrelationIdClientHttpInterceptor.class);
                });
    }

    @Test
    @DisplayName("no jakarta.servlet on classpath (WebFlux gateway): skips the filter, no introspection crash")
    void nonServletClasspath_skipsFilterWithoutCrashing() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(jakarta.servlet.Filter.class))
                .withConfiguration(AutoConfigurations.of(CorrelationIdAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("correlationIdFilter");
                    assertThat(ctx).hasSingleBean(CorrelationIdClientHttpInterceptor.class);
                });
    }
}
