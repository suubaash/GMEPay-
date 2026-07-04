package com.gme.pay.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Inbound correlation-id establishment for SERVLET services (17 of the 18 — everything but
 * api-gateway). Split out of {@link CorrelationIdAutoConfiguration} because this class references
 * {@code jakarta.servlet} types: auto-configuration classes are condition-filtered from ASM
 * metadata before being loaded, so keeping the servlet imports behind the class-level
 * {@code @ConditionalOnClass} is what lets the reactive gateway boot without
 * {@code jakarta.servlet.Filter} on its classpath.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnProperty(prefix = "gmepay.correlation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CorrelationIdServletAutoConfiguration {

    /**
     * Runs before {@code InternalAuthFilter} (HIGHEST_PRECEDENCE+10) and the RBAC context filter
     * (HIGHEST_PRECEDENCE+20), so the correlation id is in MDC before any auth/RBAC logging occurs.
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.addUrlPatterns("/*");
        reg.setName("correlationIdFilter");
        return reg;
    }
}
