package com.gme.sim.gmeremit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GmeremitSimConfig {

    @Bean
    public RestClient gmepayRestClient(
            @Value("${gmepay.sim.gmeremit.gmepay-base-url:http://localhost:8084}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Separate client for the scheme simulator's QR-decode endpoint
     * ({@code POST /v1/scheme/qr/decode}), which lives on the scheme network sim
     * (sim-scheme :9102), NOT on the payment-executor hub. The wallet's "scan"
     * preview uses this; payments still go to the hub via {@link #gmepayRestClient}.
     */
    @Bean
    public RestClient schemeRestClient(
            @Value("${gmepay.sim.gmeremit.scheme-base-url:http://localhost:9102}") String schemeBaseUrl) {
        return RestClient.builder()
                .baseUrl(schemeBaseUrl)
                .build();
    }
}
