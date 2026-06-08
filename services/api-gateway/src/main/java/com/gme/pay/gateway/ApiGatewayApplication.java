package com.gme.pay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the GMEPay+ API Gateway.
 *
 * <p>This is the single HTTPS edge for all partner traffic (api.gmepayplus.com /
 * api-sandbox.gmepayplus.com). It enforces HMAC-SHA256 request signing, idempotency-key
 * presence, per-partner rate limiting, replay protection, IP allowlisting, and routes
 * verified traffic to downstream microservices via Spring Cloud Gateway.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
