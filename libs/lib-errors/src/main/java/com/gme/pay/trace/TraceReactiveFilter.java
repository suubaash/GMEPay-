package com.gme.pay.trace;

import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) counterpart of {@link TraceServletFilter}, for the API gateway and any
 * other reactive service. Reports each request as it completes.
 */
public class TraceReactiveFilter implements WebFilter {

    private final TraceReporter reporter;

    public TraceReactiveFilter(TraceReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (TraceNames.skip(path)) {
            return chain.filter(exchange);
        }
        long t0 = System.nanoTime();
        String method = exchange.getRequest().getMethod().name();
        String caller = exchange.getRequest().getHeaders()
                .getFirst(TraceClientHttpInterceptor.CALLER_HEADER);
        final String from = (caller == null || caller.isBlank()) ? "external" : caller;
        return chain.filter(exchange).doFinally(sig -> {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
            reporter.report(from, reporter.self(), method, path, status, ms, null, null);
        });
    }
}
