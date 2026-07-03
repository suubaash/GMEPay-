package com.gme.pay.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the per-request correlation id at the very edge of the servlet chain.
 *
 * <ol>
 *   <li>Reads an incoming {@link CorrelationHeaders#CORRELATION_ID} (or the
 *       {@link CorrelationHeaders#REQUEST_ID} alias); if absent/blank, generates a UUID.</li>
 *   <li>Puts it in SLF4J {@link MDC} under {@link CorrelationHeaders#MDC_KEY} so every log line
 *       for this request (including auth-failure logs from later filters) carries it.</li>
 *   <li>Echoes it back on the {@link CorrelationHeaders#CORRELATION_ID} response header so callers
 *       and browsers can see the id that identifies their request.</li>
 *   <li>ALWAYS clears the MDC key in a {@code finally} — the request threads are pooled, so a stale
 *       id must never leak into the next request served by the same thread.</li>
 * </ol>
 *
 * <p>Registered with an early filter order (before {@code InternalAuthFilter} and the RBAC context
 * filter) so a rejected request is still logged with its correlation id.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String id = firstNonBlank(
                request.getHeader(CorrelationHeaders.CORRELATION_ID),
                request.getHeader(CorrelationHeaders.REQUEST_ID));
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationHeaders.MDC_KEY, id);
        // Echo before the chain runs so the header is present even if a downstream filter short-circuits.
        response.setHeader(CorrelationHeaders.CORRELATION_ID, id);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationHeaders.MDC_KEY);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }
}
