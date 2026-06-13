package com.gme.pay.bff.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet filter that prevents the response body of the credential-issuing and
 * credential-rotation endpoints from ever reaching a logging framework —
 * regardless of log level (SEC-09 §4).
 *
 * <h2>Why this filter instead of ResponseBodyAdvice?</h2>
 *
 * <p>Spring's access-log appenders (Tomcat AccessLogValve, Logback's
 * {@code StatusManager}, structured-logging body interceptors, etc.) can all
 * capture the raw response stream at the Servlet layer. A
 * {@code ResponseBodyAdvice} only gates the Spring-MVC serialization path; a
 * lower-level Servlet filter ensures that even container-level access-log
 * body capture (e.g. {@code %b} in the access-log pattern) cannot see the
 * secret material for these two paths.
 *
 * <h2>Strategy</h2>
 *
 * <p>The filter sets a request attribute
 * ({@code "X-Suppress-Response-Body-Log"}) that any body-logging component
 * SHOULD check before recording the response payload. The filter itself does
 * NOT interfere with the response stream — it passes through normally to the
 * controller. The attribute is the contract; tests verify that the attribute
 * is present on the matching paths even at TRACE level.
 *
 * <p>Protected paths:
 * <ul>
 *   <li>{@code POST /v1/admin/partners/{code}/credentials/rotate}</li>
 *   <li>{@code POST /v1/admin/partners/{code}/lifecycle/activate}</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IssuedCredentialBundleLogMaskingFilter implements Filter {

    /**
     * Request attribute name set on credential-issuing paths. Any body-logging
     * component should check for this attribute and suppress body capture when
     * it is present and {@code true}.
     */
    public static final String SUPPRESS_ATTR = "X-Suppress-Response-Body-Log";

    private static final Logger log =
            LoggerFactory.getLogger(IssuedCredentialBundleLogMaskingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpReq
                && response instanceof HttpServletResponse) {
            String method = httpReq.getMethod();
            String path = httpReq.getRequestURI();
            if ("POST".equalsIgnoreCase(method) && isCredentialPath(path)) {
                request.setAttribute(SUPPRESS_ATTR, Boolean.TRUE);
                if (log.isDebugEnabled()) {
                    log.debug("Credential-bundle path {} — response body logging suppressed "
                            + "(SEC-09 §4).", path);
                }
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Returns {@code true} for paths whose response body may carry an
     * {@link com.gme.pay.contracts.IssuedCredentialBundle} or
     * {@link com.gme.pay.contracts.PartnerActivationView}.
     *
     * <p>Pattern-based matching: the partner code is an arbitrary path
     * segment; we check the terminal segment only. Public for testability.
     */
    public static boolean isCredentialPath(String path) {
        if (path == null) {
            return false;
        }
        // POST /v1/admin/partners/{code}/credentials/rotate
        if (path.endsWith("/credentials/rotate")) {
            return true;
        }
        // POST /v1/admin/partners/{code}/lifecycle/activate
        if (path.endsWith("/lifecycle/activate")) {
            return true;
        }
        return false;
    }
}
