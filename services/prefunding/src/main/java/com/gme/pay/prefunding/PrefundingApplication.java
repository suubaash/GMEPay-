package com.gme.pay.prefunding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Prefunding/Balance service: prepaid USD balances, atomic deduction, low-balance alerts. */
@SpringBootApplication
public class PrefundingApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrefundingApplication.class, args);
    }
}
