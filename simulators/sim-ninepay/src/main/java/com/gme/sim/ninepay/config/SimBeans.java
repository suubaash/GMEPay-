package com.gme.sim.ninepay.config;

import com.gme.sim.ninepay.model.Scenario;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Singleton scenario state shared by the service controller, lifecycle and /sim admin API. */
@Configuration
public class SimBeans {

    @Bean
    public Scenario scenario() {
        return new Scenario();
    }
}
