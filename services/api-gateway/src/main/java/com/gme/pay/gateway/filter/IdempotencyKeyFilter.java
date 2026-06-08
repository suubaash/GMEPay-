package com.gme.pay.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * GlobalFilter — Idempotency-Key header presence and format enforcement for POST requests
 * (API-05 §2.6).
 *
 * <p>Execution order: 7 (after ReplayProtectionFilter, before ContentTypeFilter).
 *
 * <p>Delegates validation logic to the plain {@link IdempotencyKeyValidator} class so the
 * rules can be unit-tested without a Reactor context.
 */
@Component
public class IdempotencyKeyFilter implements GlobalFilter, Ordered {

    public static final int ORDER = 7;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Only POST requests require Idempotency-Key
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        String key = exchange.getRequest().getHeaders().getFirst("Idempotency-Key");
        IdempotencyKeyValidator.ValidationResult result = IdempotencyKeyValidator.validate(key);

        return switch (result) {
            case MISSING -> GatewayErrorWriter.writeError(
                    exchange, HttpStatus.BAD_REQUEST,
                    "MISSING_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required for POST requests");

            case INVALID_LENGTH -> GatewayErrorWriter.writeError(
                    exchange, HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Idempotency-Key length must be between 16 and 128 characters",
                    List.of(Map.of(
                            "field", "Idempotency-Key",
                            "issue", "length must be 16-128 chars")));

            case VALID -> chain.filter(exchange);
        };
    }
}
