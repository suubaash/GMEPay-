package com.gme.pay.bff.config;

import com.gme.pay.bff.web.OpsRbacGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AdminSurfaceRbacInterceptor} over the admin URL space so every
 * {@code /v1/admin/**} endpoint — including the partner-onboarding controllers that carried no
 * authorization at all — is RBAC-gated by default (gap register T0-2 / T0-3).
 *
 * <p>{@code /v1/portal/**} is deliberately NOT covered here: portal endpoints are authorized by
 * tenant scope against the token's partner claim, which needs the {@code {partnerId}} path
 * variable, so {@link com.gme.pay.bff.web.PartnerPortalController} calls
 * {@link OpsRbacGuard#requirePartnerScope(String)} per handler.
 */
@Configuration
public class BffWebMvcConfig implements WebMvcConfigurer {

    /** URL space guarded by the coarse platform-operator gate. */
    static final String ADMIN_PATH_PATTERN = "/v1/admin/**";

    private final OpsRbacGuard rbac;

    public BffWebMvcConfig(OpsRbacGuard rbac) {
        this.rbac = rbac;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminSurfaceRbacInterceptor(rbac))
                .addPathPatterns(ADMIN_PATH_PATTERN);
    }
}
