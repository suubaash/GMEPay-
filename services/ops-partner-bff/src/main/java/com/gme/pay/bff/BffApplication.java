package com.gme.pay.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ops/Partner BFF — aggregation API for the Admin UI and Partner Self-Service Portal.
 *
 * <p>This service is a CLIENT of backend services (over REST). It does not own a
 * database; it composes responses from config-registry, transaction-mgmt,
 * prefunding, revenue-ledger and settlement-reconciliation into UI-shaped DTOs
 * (see {@code com.gme.pay.bff.web}).
 *
 * <p>See {@code docs/INTER_SERVICE_CONTRACTS.md} (architecture diagram) and
 * {@code docs/SERVICE_MAP.md} for the role of this service in the platform.
 */
@SpringBootApplication
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
