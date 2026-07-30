package com.gme.pay.registry.actor;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuditActorArgumentResolver} so {@link AuditActorHeader} parameters
 * resolve.
 *
 * <p>If this configuration is ever excluded from a context that also loads the controllers,
 * Spring will fail to resolve the annotated parameters rather than quietly passing
 * {@code null} — which is the desired failure direction for a security-relevant binding.
 * MockMvc standalone setups must register the resolver explicitly; see
 * {@code AuditActorResolutionTest} for the pattern.
 */
@Configuration
public class ActorWebConfig implements WebMvcConfigurer {

    private final AuditActorResolver resolver;

    public ActorWebConfig(AuditActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuditActorArgumentResolver(resolver));
    }
}
