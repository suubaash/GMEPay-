package com.gme.pay.txn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Transaction Management microservice.
 * <p>
 * Owns the transaction store (PostgreSQL {@code txn} DB), the state machine
 * (CREATED → PENDING_DEBIT → APPROVED / FAILED / CANCELLED), and the
 * transactional outbox for domain events.
 */
@SpringBootApplication
@EnableScheduling
public class TransactionMgmtApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionMgmtApplication.class, args);
    }
}
