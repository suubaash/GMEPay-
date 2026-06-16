package com.gme.pay.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records every inbound HTTP request handled by a servlet (MVC) service. The service
 * reports itself as the callee, so it appears in the dashboard the moment it serves any
 * non-noise request — this is the universal "service is alive and traced" signal.
 */
public class TraceServletFilter extends OncePerRequestFilter {

    private final TraceReporter reporter;

    public TraceServletFilter(TraceReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (TraceNames.skip(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        long t0 = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String caller = header(request, TraceClientHttpInterceptor.CALLER_HEADER, "external");
            reporter.report(caller, reporter.self(), request.getMethod(), path,
                    response.getStatus(), ms, null, null);
        }
    }

    private static String header(HttpServletRequest req, String name, String dflt) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
