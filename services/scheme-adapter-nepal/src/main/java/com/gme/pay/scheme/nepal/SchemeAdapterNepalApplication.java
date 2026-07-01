package com.gme.pay.scheme.nepal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Nepal QR scheme adapter service.
 *
 * <p>This service is the Anti-Corruption Layer (ACL) for the Nepal QR payment scheme
 * (Khalti / Fonepay). It translates payment-executor's canonical
 * {@code /internal/scheme/nepal/...} calls into the partner's Khalti Scan&amp;Pay
 * REST API (decode/validate &rarr; pay &rarr; status), the real counterpart to
 * {@code sim-nepal-qr} (:9103).</p>
 *
 * <p>Unlike ZeroPay, the Nepal {@code pay} call is <b>synchronous single-shot</b>:
 * there is no separate authorize/commit. The adapter therefore exposes a single
 * {@code /submit} that authorizes+commits in one round-trip.</p>
 */
@SpringBootApplication
public class SchemeAdapterNepalApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemeAdapterNepalApplication.class, args);
    }
}
