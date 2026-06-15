package com.gme.pay.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reporting & Compliance service.
 * Exposes GET /v1/reports and produces BOK FX1014/FX1015 exports.
 * Consumes transaction-mgmt and revenue-ledger via API (never their DB).
 *
 * <p>{@code @EnableScheduling} activates all {@code @Scheduled} components in this
 * service — including {@link com.gme.pay.reporting.bok.BokReportScheduler} (LANE A)
 * and any future Hometax/KoFIU schedulers added by other lanes. LANE A owns this
 * annotation; other lanes must NOT add it again.
 */
@SpringBootApplication
@EnableScheduling
public class ReportingComplianceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportingComplianceApplication.class, args);
    }
}
