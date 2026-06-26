package com.gme.pay.internalauth;

import com.gme.pay.errors.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service internal-auth gate. On the configured internal-only path patterns, a request
 * is admitted only if it carries the shared {@link InternalAuthHeaders#INTERNAL_TOKEN} matching the
 * service's configured secret; otherwise it is rejected with {@code 401} before any controller runs.
 *
 * <p><b>Why (#90):</b> auth-identity exposes {@code /v1/rbac/**} (RBAC resolution + management) and
 * {@code /v1/approvals/**} (approval decisions, which trust an {@code X-Gme-Permissions} header).
 * These are reached server-to-server by the api-gateway's claim resolver and the ops BFF — NOT through
 * the gateway's strip/stamp filter — so without this gate any actor with network reach could call
 * {@code /v1/rbac/resolve} to enumerate the authority matrix, mutate the RBAC catalogue, or forge an
 * approval. The trusted callers present the secret; everyone else is refused. Constant-time secret
 * comparison (mirrors {@code RbacClaimSigner.verify}).
 *
 * <p>This is an application-layer stopgap for the absence of a network-policy / mTLS mesh; it does not
 * replace network isolation, and it asserts only "a trusted internal service is calling" — operator-level
 * authority (does this human hold {@code rbac.manage}?) is enforced separately upstream.
 */
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final String secret;
    private final List<String> protectedPatterns;

    public InternalAuthFilter(String secret, List<String> protectedPatterns) {
        this.secret = secret == null ? "" : secret;
        this.protectedPatterns = protectedPatterns == null ? List.of() : List.copyOf(protectedPatterns);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isProtected(request.getRequestURI())) {
            String token = request.getHeader(InternalAuthHeaders.INTERNAL_TOKEN);
            if (!secretMatches(token)) {
                log.warn("internal-auth: refused direct call to {} — missing/invalid {} (not a trusted "
                        + "internal caller)", request.getRequestURI(), InternalAuthHeaders.INTERNAL_TOKEN);
                writeUnauthorized(response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** True when the URI matches any configured internal-only pattern. Package-private for tests. */
    boolean isProtected(String uri) {
        if (uri == null) {
            return false;
        }
        for (String pattern : protectedPatterns) {
            if (MATCHER.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /** Constant-time comparison of the presented token against the configured secret. Package-private for tests. */
    boolean secretMatches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes the standard {@code ApiError} envelope ({@code {code,message,retryable,requestId}}) as 401. */
    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":\"" + ErrorCode.UNAUTHORIZED.name() + "\","
                        + "\"message\":\"internal service authentication required\","
                        + "\"retryable\":false,\"requestId\":null}");
    }
}
