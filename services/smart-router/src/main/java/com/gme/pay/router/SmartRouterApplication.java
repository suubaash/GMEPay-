package com.gme.pay.router;

import com.gme.pay.domain.routing.PartnerSchemeResolver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Smart Router service: maps a merchant country / partner to its QR scheme(s). */
@SpringBootApplication
public class SmartRouterApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartRouterApplication.class, args);
    }

    /**
     * The routing engine over the data-driven {@link PartnerSchemeResolver}
     * (Slice 7). The production resolver is the component-scanned
     * {@code RestPartnerSchemeResolver} reading config-registry's
     * {@code partner_scheme} registry over REST.
     */
    @Bean
    public SchemeRouter schemeRouter(PartnerSchemeResolver resolver) {
        return new SchemeRouter(resolver);
    }
}
