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
    private final String merchantQrDataBaseUrl;

    // Spring 6: @Autowired required when a class has 2+ constructors.
    // This class has one constructor so no annotation is needed, but we're
    // binding via @Value rather than @ConfigurationProperties to stay simple.
    public MerchantConfig(
            @org.springframework.beans.factory.annotation.Value(
                    "${gmepay.sim.merchant.scheme-base-url:http://localhost:9102}")
            String schemeBaseUrl,
            @org.springframework.beans.factory.annotation.Value(
                    "${gmepay.sim.merchant.merchant-qr-data-base-url:http://localhost:18083}")
            String merchantQrDataBaseUrl) {
        this.schemeBaseUrl = schemeBaseUrl;
        this.merchantQrDataBaseUrl = merchantQrDataBaseUrl;
    }

    public String getSchemeBaseUrl() {
        return schemeBaseUrl;
    }

    public String getMerchantQrDataBaseUrl() {
        return merchantQrDataBaseUrl;
    }

    @Bean
    public RestClient schemeRestClient() {
        return RestClient.builder()
                .baseUrl(schemeBaseUrl)
                .build();
    }

    /**
     * Client for merchant-qr-data ({@code POST /v1/merchants}) — used to mirror a freshly
     * registered terminal shop's QR into the payment-side lookup store, so the wallet's
     * scanned QR resolves at pay time instead of 404-ing.
     */
    @Bean
    public RestClient merchantQrDataRestClient() {
        return RestClient.builder()
                .baseUrl(merchantQrDataBaseUrl)
                .build();
    }
}
