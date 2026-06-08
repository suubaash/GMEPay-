package com.gme.pay.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Revenue Ledger service — double-entry ledger posting, 70/30 scheme-fee share, FX-margin capture (WBS 7.2, 7.3). */
@SpringBootApplication
public class RevenueLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RevenueLedgerApplication.class, args);
    }
}
