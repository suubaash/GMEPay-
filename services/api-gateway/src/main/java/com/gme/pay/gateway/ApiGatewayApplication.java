package com.gme.pay.gateway;

import com.gme.pay.gateway.partner.ConfigPartnerCredentialProperties;
import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the GMEPay+ API Gateway.
 *
 * <p>This is the single HTTPS edge for all partner traffic (api.gmepayplus.com /
 * api-sandbox.gmepayplus.com). It enforces HMAC-SHA256 request signing, idempotency-key
 * presence, per-partner rate limiting, replay protection, IP allowlisting, and routes
 * verified traffic to downstream microservices via Spring Cloud Gateway.
 */
@SpringBootApplication
@EnableConfigurationProperties({RateLimitProperties.class, ConfigPartnerCredentialProperties.class})
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
