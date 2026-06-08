package com.gme.pay.scheme.zeropay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot entry point for the ZeroPay scheme adapter service.
 *
 * <p>This service is the Anti-Corruption Layer (ACL) for the ZeroPay payment scheme.
 * It owns the ZP00xx batch file format/parse logic and exposes an internal REST API
 * consumed by payment-executor.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties
public class SchemeAdapterZeroPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemeAdapterZeroPayApplication.class, args);
    }
}
