package com.gme.pay.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Reporting & Compliance service.
 * Exposes GET /v1/reports and produces BOK FX1014/FX1015 exports.
 * Consumes transaction-mgmt and revenue-ledger via API (never their DB).
 */
@SpringBootApplication
public class ReportingComplianceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingComplianceApplication.class, args);
    }
}
