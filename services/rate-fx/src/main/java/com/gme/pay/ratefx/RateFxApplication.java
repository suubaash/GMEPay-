package com.gme.pay.ratefx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Rate & FX Engine service (RATE-04). Exposes the rate engine over REST. */
@SpringBootApplication
public class RateFxApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateFxApplication.class, args);
    }
}
