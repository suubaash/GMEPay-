package com.gme.pay.correlation;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive twin of {@link CorrelationIdFilter} for WebFlux services (api-gateway). Establishes
 * the correlation id at the very edge:
 *
 * <ol>
 *   <li>Reads an incoming {@link CorrelationHeaders#CORRELATION_ID} (or the
 *       {@link CorrelationHeaders#REQUEST_ID} alias); if absent/blank, generates a UUID.</li>
 *   <li>Mutates the forwarded request to carry {@code X-Correlation-Id}, so the gateway's
 *       proxied downstream call hands the SAME id to the target service's servlet filter —
 *       one id spans edge → service → service.</li>
 *   <li>Echoes it on the response header so callers see the id that identifies their request.</li>
 *   <li>Briefly stamps SLF4J MDC for logs emitted during filter assembly, restoring the previous
 *       value in a {@code finally}. (Reactor hops threads, so MDC is best-effort here; the
 *       header propagation above is the load-bearing part.)</li>
 * </ol>
 */
public class CorrelationIdWebFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = firstNonBlank(
                exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID),
                exchange.getRequest().getHeaders().getFirst(CorrelationHeaders.REQUEST_ID));
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(CorrelationHeaders.CORRELATION_ID, id)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
        // Echo before the chain runs so the header is present even if a later filter short-circuits.
        mutatedExchange.getResponse().getHeaders().set(CorrelationHeaders.CORRELATION_ID, id);

        String previous = MDC.get(CorrelationHeaders.MDC_KEY);
        MDC.put(CorrelationHeaders.MDC_KEY, id);
        try {
            return chain.filter(mutatedExchange);
        } finally {
            if (previous == null) {
                MDC.remove(CorrelationHeaders.MDC_KEY);
            } else {
                MDC.put(CorrelationHeaders.MDC_KEY, previous);
            }
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
