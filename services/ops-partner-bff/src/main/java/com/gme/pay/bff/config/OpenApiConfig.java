package com.gme.pay.bff.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc bootstrap. {@code GET /v3/api-docs} returns the OpenAPI 3 JSON;
 * {@code GET /swagger-ui.html} opens the UI. See {@code application.properties}
 * for the path overrides.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI gmepayOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("GMEPay+ — ops-partner-bff")
                .version("0.1.0")
                .description("Aggregation API for Admin UI and Partner Self-Service Portal. "
                        + "This BFF orchestrates read calls to backend services; it does not own data. "
                        + "See docs/INTER_SERVICE_CONTRACTS.md."));
    }
}
