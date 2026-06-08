package com.gme.pay.merchant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Merchant & QR Data service (WBS 9.3). Exposes merchant/QR lookup over REST. */
@SpringBootApplication
public class MerchantQrDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantQrDataApplication.class, args);
    }
}
