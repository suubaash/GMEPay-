package com.gme.pay.scheme.ninepay.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Springdoc bootstrap. GET /v3/api-docs returns the OpenAPI 3 JSON; GET /swagger-ui.html opens the UI. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI gmepayOpenAPI() {
        return new OpenAPI().info(new Info().title("GMEPay+ — scheme-adapter-ninepay").version("0.1.0")
                .description("REST API for the scheme-adapter-ninepay microservice — ACL over the 9Pay "
                        + "(Vietnam) disbursement/payout scheme: VND transfers to banks/cards/wallets, "
                        + "balance, VIETQR/VNPAY decode, and the inbound 9Pay IPN edge."));
    }
}
