package com.gme.pay.auth.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Springdoc bootstrap. GET /v3/api-docs returns the OpenAPI 3 JSON; GET /swagger-ui.html opens the UI. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI gmepayOpenAPI() {
        return new OpenAPI().info(new Info().title("GMEPay+ — auth-identity").version("0.1.0")
                .description("REST API for the auth-identity microservice. See docs/INTER_SERVICE_CONTRACTS.md."));
    }
}
