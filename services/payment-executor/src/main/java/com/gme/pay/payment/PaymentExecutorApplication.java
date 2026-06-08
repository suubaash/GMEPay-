package com.gme.pay.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Payment Executor service — orchestrates the live payment path (WBS 5.2 / 5.5 / 5.6 / 5.8). */
@SpringBootApplication
public class PaymentExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentExecutorApplication.class, args);
    }
}
