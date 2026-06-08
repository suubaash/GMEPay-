package com.gme.pay.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Auth & Identity microservice.
 * Exposes: POST /internal/auth/verify (HMAC-SHA256 partner request-signature verification),
 *          JWT issue/verify helpers for internal operator sessions.
 */
@SpringBootApplication
public class AuthIdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthIdentityApplication.class, args);
    }
}
