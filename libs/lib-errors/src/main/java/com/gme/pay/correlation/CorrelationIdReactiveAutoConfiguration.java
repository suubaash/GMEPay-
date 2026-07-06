package com.gme.pay.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Inbound correlation-id establishment for REACTIVE services (api-gateway). Registers
 * {@link CorrelationIdWebFilter} so the gateway echoes the id to callers and forwards it on the
 * proxied request, joining the fleet-wide correlation chain established by
 * {@link CorrelationIdServletAutoConfiguration} in the servlet services. Kept separate for the
 * same classpath-safety reason as the servlet twin — see its javadoc.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
@ConditionalOnProperty(prefix = "gmepay.correlation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CorrelationIdReactiveAutoConfiguration {

    @Bean
    public CorrelationIdWebFilter correlationIdWebFilter() {
        return new CorrelationIdWebFilter();
    }
}
