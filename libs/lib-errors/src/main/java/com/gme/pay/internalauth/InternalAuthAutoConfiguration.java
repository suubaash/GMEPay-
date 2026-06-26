package com.gme.pay.internalauth;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for the service-to-service internal-auth gate. When
 * {@code gmepay.internal-auth.enabled=true} on a servlet service it installs, with zero per-service
 * code, an {@link InternalAuthFilter} that refuses any request to a configured internal-only path
 * pattern unless it carries the shared {@link InternalAuthHeaders#INTERNAL_TOKEN}.
 *
 * <p>Discovered via {@code META-INF/spring/...AutoConfiguration.imports} (alongside the tracer and
 * RBAC), so any service depending on lib-errors gets it for free. Off by default; only services that
 * expose internal-only endpoints (today: auth-identity) opt in.
 *
 * <p>Fail-closed: a service that enables the gate without a secret would silently admit everyone, so
 * it refuses to start instead.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "gmepay.internal-auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthAutoConfiguration {

    @Bean
    public InternalAuthConfigValidator internalAuthConfigValidator(InternalAuthProperties props) {
        return new InternalAuthConfigValidator(props);
    }

    @Bean
    public FilterRegistrationBean<InternalAuthFilter> internalAuthFilter(InternalAuthProperties props) {
        InternalAuthFilter filter = new InternalAuthFilter(props.getSecret(), props.getPathPatterns());
        FilterRegistrationBean<InternalAuthFilter> reg = new FilterRegistrationBean<>(filter);
        // Run before the RBAC context filter (HIGHEST_PRECEDENCE+20) and well before MVC dispatch, so an
        // un-trusted direct caller is rejected before any controller or RBAC reconstruction runs.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.addUrlPatterns("/*");
        reg.setName("internalAuthFilter");
        return reg;
    }

    /** Fail-closed: enabling the gate without a secret would admit everyone — refuse to start. */
    static class InternalAuthConfigValidator implements InitializingBean {

        private final InternalAuthProperties props;

        InternalAuthConfigValidator(InternalAuthProperties props) {
            this.props = props;
        }

        @Override
        public void afterPropertiesSet() {
            if (props.getSecret() == null || props.getSecret().isBlank()) {
                throw new IllegalStateException(
                        "gmepay.internal-auth.enabled=true requires gmepay.internal-auth.secret (the shared "
                        + "service-to-service token the gateway resolver / ops BFF present in the "
                        + InternalAuthHeaders.INTERNAL_TOKEN + " header). Set it (matching the callers') or "
                        + "set gmepay.internal-auth.enabled=false.");
            }
        }
    }
}
