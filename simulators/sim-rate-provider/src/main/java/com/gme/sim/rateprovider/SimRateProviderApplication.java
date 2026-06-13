package com.gme.sim.rateprovider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sim-Rate-Provider: fake xe.com-like live FX rate feed (port 9101).
 * Used by the rate-fx service via XeRateClient to close audit gap B4.
 */
@SpringBootApplication
@EnableScheduling
public class SimRateProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimRateProviderApplication.class, args);
    }
}
