package com.gme.pay.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for end-to-end correlation-ID propagation (outbound side). On by default
 * ({@code gmepay.correlation.enabled}, {@code matchIfMissing=true}); set it to {@code false} to
 * disable. Installs, with zero per-service code, a shared
 * {@link CorrelationIdClientHttpInterceptor} bean plus, when {@code RestClient} /
 * {@code RestTemplate} is on the classpath, customizers that register it on the
 * Spring-Boot-provided builders so outbound calls propagate the id automatically.
 *
 * <p>The inbound side lives in web-stack-specific auto-configs so this class stays loadable on
 * any classpath: {@link CorrelationIdServletAutoConfiguration} (servlet services) and
 * {@link CorrelationIdReactiveAutoConfiguration} (WebFlux, i.e. api-gateway). This class MUST NOT
 * reference servlet or reactive types — it is introspected in every service, including the
 * reactive gateway where {@code jakarta.servlet.Filter} is absent.
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
