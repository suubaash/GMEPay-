package com.gme.pay.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The reactive edge must behave exactly like the servlet {@link CorrelationIdFilter}: reuse an
 * inbound id (canonical header or alias), otherwise mint one; forward it on the mutated request
 * (this is what carries the id through the gateway to downstream services); and echo it on the
 * response even when the chain short-circuits.
 */
class CorrelationIdWebFilterTest {

    private final CorrelationIdWebFilter filter = new CorrelationIdWebFilter();

    @Test
    void reusesInboundCorrelationId_andForwardsItDownstream() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/pay").header(CorrelationHeaders.CORRELATION_ID, "abc-123"));
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        filter.filter(exchange, e -> { seen.set(e); return Mono.empty(); }).block();

        assertThat(seen.get().getRequest().getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID))
                .isEqualTo("abc-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID))
                .isEqualTo("abc-123");
    }

    @Test
    void acceptsRequestIdAlias() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/pay").header(CorrelationHeaders.REQUEST_ID, "alias-9"));
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        filter.filter(exchange, e -> { seen.set(e); return Mono.empty(); }).block();

        assertThat(seen.get().getRequest().getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID))
                .isEqualTo("alias-9");
    }

    @Test
    void generatesUuid_whenNoInboundId_andEchoesBeforeChainRuns() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/v1/pay"));

        // Chain short-circuits (e.g. auth rejection) — header must already be on the response.
        filter.filter(exchange, e -> Mono.empty()).block();

        String echoed = exchange.getResponse().getHeaders().getFirst(CorrelationHeaders.CORRELATION_ID);
        assertThat(echoed).isNotBlank();
        assertThat(echoed).hasSize(36); // UUID shape
    }
}
