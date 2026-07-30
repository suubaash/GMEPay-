package com.gme.pay.registry.actor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Binds every {@link AuditActorHeader}-annotated controller parameter to
 * {@link AuditActorResolver#resolve}.
 *
 * <p>One class replaces the 28 raw {@code @RequestHeader(value = "X-Actor", required = false)}
 * bindings that used to hand unauthenticated client input straight to the services that write
 * the audit log. The value handed to a controller is now always a well-formed
 * {@link com.gme.pay.audit.AuditActors} principal, so the {@code DEFAULT_ACTOR} fallbacks in
 * the service layer are unreachable — they are retained only as a belt to this brace, and
 * they no longer spell {@code "system"}.
 *
 * <p>Registered by {@link ActorWebConfig}. Only {@code String} parameters are supported;
 * annotating anything else is a wiring mistake and fails fast at request time rather than
 * silently injecting null.
 */
public class AuditActorArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuditActorResolver resolver;

    public AuditActorArgumentResolver(AuditActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuditActorHeader.class)
                || parameter.hasParameterAnnotation(AuditActorIp.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        boolean wantsIp = parameter.hasParameterAnnotation(AuditActorIp.class);
        if (!String.class.equals(parameter.getParameterType())) {
            throw new IllegalStateException(
                    "@" + (wantsIp ? "AuditActorIp" : "AuditActorHeader")
                            + " may only annotate a String parameter, found "
                            + parameter.getParameterType() + " on "
                            + parameter.getMethod());
        }
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return wantsIp ? resolver.resolveIp(request) : resolver.resolve(request);
    }
}
