package com.gme.pay.rbac;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gme.pay.rbac.constraint.ConstraintEngine;

/**
 * Auto-configuration for cross-service RBAC enforcement. When {@code gmepay.rbac.enabled=true}
 * on a servlet service it installs, with zero per-service code:
 * <ul>
 *   <li>{@link RbacContextFilter} — rebuilds the caller's {@link PermissionContext} from the
 *       edge-stamped headers and binds it to the request thread;</li>
 *   <li>{@link RbacPermissionInterceptor} — enforces {@link RequiresPermission} on handlers
 *       (AUDIT logs, ENFORCE returns 403), then narrows a granted permission with any dynamic
 *       constraints from the {@link ConstraintSource} via the {@link ConstraintEngine};</li>
 *   <li>a default {@link HeaderConstraintSource} (decodes edge-stamped constraints — harmless
 *       when the header is absent) and a shared {@link ConstraintEngine};</li>
 *   <li>a default {@link RbacDecisionListener.Logging} (override with your own bean to forward
 *       decisions to the audit pipeline).</li>
 * </ul>
 * Discovered via {@code META-INF/spring/...AutoConfiguration.imports} (alongside the tracer),
 * so any service depending on lib-errors gets it for free. Off by default.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "gmepay.rbac", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RbacProperties.class)
public class RbacAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RbacDecisionListener.class)
    public RbacDecisionListener rbacDecisionListener() {
        return new RbacDecisionListener.Logging();
    }

    /** Fail-closed: a service that ENFORCEs RBAC without a verification secret would silently trust forged headers. */
    @Bean
    public RbacConfigValidator rbacConfigValidator(RbacProperties props) {
        return new RbacConfigValidator(props);
    }

    static class RbacConfigValidator implements org.springframework.beans.factory.InitializingBean {

        private final RbacProperties props;

        RbacConfigValidator(RbacProperties props) {
            this.props = props;
        }

        @Override
        public void afterPropertiesSet() {
            if (props.getMode() == RbacMode.ENFORCE
                    && props.getVerify().getSecret().isBlank()
                    && !props.getVerify().isAllowUnsigned()) {
                throw new IllegalStateException(
                        "gmepay.rbac.mode=ENFORCE requires gmepay.rbac.verify.secret (HMAC claim provenance / "
                        + "anti-spoof). Set it to match the gateway's gmepay.rbac.stamp.secret, use mode=audit, "
                        + "or explicitly set gmepay.rbac.verify.allow-unsigned=true to accept unsigned claims.");
            }
        }
    }

    @Bean
    public FilterRegistrationBean<RbacContextFilter> rbacContextFilter(RbacProperties props) {
        RbacContextFilter filter = new RbacContextFilter(
                props.getVerify().getSecret(), props.getVerify().getClockSkewMs());
        FilterRegistrationBean<RbacContextFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20); // before MVC dispatch, after request decoding
        reg.addUrlPatterns("/*");
        reg.setName("rbacContextFilter");
        return reg;
    }

    @Bean
    @ConditionalOnMissingBean(ConstraintSource.class)
    public ConstraintSource rbacConstraintSource() {
        return new HeaderConstraintSource();
    }

    @Bean
    @ConditionalOnMissingBean(ConstraintEngine.class)
    public ConstraintEngine rbacConstraintEngine() {
        return new ConstraintEngine();
    }

    @Bean
    public WebMvcConfigurer rbacInterceptorConfigurer(RbacProperties props, RbacDecisionListener listener,
                                                      ConstraintSource constraintSource,
                                                      ConstraintEngine constraintEngine) {
        RbacPermissionInterceptor interceptor =
                new RbacPermissionInterceptor(props, listener, constraintSource, constraintEngine);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
