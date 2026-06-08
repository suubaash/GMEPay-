package com.gme.pay.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway route definitions for GMEPay+ v1 API.
 *
 * <p>All routes:
 * <ul>
 *   <li>Strip the {@code /v1} prefix via {@code RewritePath}.</li>
 *   <li>Receive the {@code X-Gateway-Version: v1} header (set via global default-filter in
 *       {@code application.yml}).</li>
 * </ul>
 *
 * <p>Rate limiters, circuit breakers, and per-route timeouts are wired here once the Redis
 * and Resilience4j beans are available (T07, T20). Placeholder routes for Phase 1 below.
 */
@Configuration
public class GatewayRoutingConfig {

    @Bean
    public RouteLocator v1Routes(RouteLocatorBuilder builder) {
        return builder.routes()

                // POST /v1/rates -> rate-fx service
                .route("rate-fx-rates", r -> r
                        .path("/v1/rates/**")
                        .filters(f -> f.rewritePath("/v1/(?<segment>.*)", "/${segment}"))
                        .uri("lb://rate-fx"))

                // POST /v1/payments, POST /v1/payments/cpm/generate,
                // GET  /v1/payments/{id}, POST /v1/payments/{id}/cancel
                .route("payment-executor", r -> r
                        .path("/v1/payments/**")
                        .filters(f -> f.rewritePath("/v1/(?<segment>.*)", "/${segment}"))
                        .uri("lb://payment-executor"))

                // GET /v1/merchants/{qr}
                .route("merchant-qr-data", r -> r
                        .path("/v1/merchants/**")
                        .filters(f -> f.rewritePath("/v1/(?<segment>.*)", "/${segment}"))
                        .uri("lb://merchant-qr-data"))

                // GET /v1/balance
                .route("prefunding-balance", r -> r
                        .path("/v1/balance")
                        .filters(f -> f.rewritePath("/v1/(?<segment>.*)", "/${segment}"))
                        .uri("lb://prefunding-balance"))

                .build();
    }
}
