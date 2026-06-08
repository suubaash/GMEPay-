package com.gme.pay.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Settlement & Reconciliation service — net/gross settlement, ZP file recon, invoicing (WBS 7.1, 7.4, 7.6, 9.8). */
@SpringBootApplication
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
