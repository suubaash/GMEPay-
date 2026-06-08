package com.gme.pay.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Smart Router service: maps a merchant country to its QR scheme(s). */
@SpringBootApplication
public class SmartRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartRouterApplication.class, args);
    }
}
