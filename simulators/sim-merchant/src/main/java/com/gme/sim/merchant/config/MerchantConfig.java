package com.gme.sim.merchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for the merchant simulator.
 * Reads gmepay.sim.merchant.* from application.yml.
 */
@Configuration
public class MerchantConfig {

    private final String schemeBaseUrl;

    // Spring 6: @Autowired required when a class has 2+ constructors.
    // This class has one constructor so no annotation is needed, but we're
    // binding via @Value rather than @ConfigurationProperties to stay simple.
    public MerchantConfig(
            @org.springframework.beans.factory.annotation.Value(
                    "${gmepay.sim.merchant.scheme-base-url:http://localhost:9102}")
            String schemeBaseUrl) {
        this.schemeBaseUrl = schemeBaseUrl;
    }

    public String getSchemeBaseUrl() {
        return schemeBaseUrl;
    }

    @Bean
    public RestClient schemeRestClient() {
        return RestClient.builder()
                .baseUrl(schemeBaseUrl)
                .build();
    }
}
