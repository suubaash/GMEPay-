package com.gme.pay.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for end-to-end correlation-ID propagation. On by default
 * ({@code gmepay.correlation.enabled}, {@code matchIfMissing=true}); set it to {@code false} to
 * disable. Installs, with zero per-service code:
 * <ul>
 *   <li>a {@link CorrelationIdFilter} (servlet services only) registered very early — before
 *       {@code InternalAuthFilter} (HIGHEST_PRECEDENCE+10) and the RBAC context filter — so even
 *       auth-rejection logs carry the id;</li>
 *   <li>a shared {@link CorrelationIdClientHttpInterceptor} bean plus, when {@code RestClient} /
 *       {@code RestTemplate} is on the classpath, customizers that register it on the
 *       Spring-Boot-provided builders so outbound calls propagate the id automatically.</li>
 * </ul>
 *
 * <p>The interceptor is also a plain bean, so a service that constructs its own {@code RestClient}
 * (rather than using the shared builder) can opt in with
 * {@code .requestInterceptor(correlationIdClientHttpInterceptor)}.
 *
 * <p>Discovered via {@code META-INF/spring/...AutoConfiguration.imports}, so every service depending
 * on lib-errors gets it for free. Purely additive: no change to error, RBAC, internal-auth, or trace
 * behaviour.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "gmepay.correlation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CorrelationIdAutoConfiguration {

    /**
     * Runs before {@code InternalAuthFilter} (HIGHEST_PRECEDENCE+10) and the RBAC context filter
     * (HIGHEST_PRECEDENCE+20), so the correlation id is in MDC before any auth/RBAC logging occurs.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        reg.setName("correlationIdFilter");
        return reg;
    }

    /** Shared interceptor bean — usable directly on service-built {@code RestClient}s. */
    @Bean
    public CorrelationIdClientHttpInterceptor correlationIdClientHttpInterceptor() {
        return new CorrelationIdClientHttpInterceptor();
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    public RestClientCustomizer correlationIdRestClientCustomizer(
            CorrelationIdClientHttpInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestTemplate")
    public RestTemplateCustomizer correlationIdRestTemplateCustomizer(
            CorrelationIdClientHttpInterceptor interceptor) {
        return template -> template.getInterceptors().add(interceptor);
    }
}
