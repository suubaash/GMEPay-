package com.gme.pay.bff.client.rest;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * REST-client infrastructure beans for the BFF's upstream adapters.
 *
 * <p>The {@link org.springframework.web.client.RestClient.Builder} that the
 * {@code Rest*Client} adapters autowire is the one autoconfigured by Spring Boot,
 * which is wired with the application's {@code ObjectMapper}. Every
 * {@link RestClientCustomizer} bean here is applied to that shared builder before
 * it is handed to each adapter.
 */
@Configuration
public class ClientBeans {

    /**
     * Switches the autoconfigured {@link org.springframework.web.client.RestClient.Builder} from the
     * default {@code SimpleClientHttpRequestFactory} (JDK {@code HttpURLConnection}) to
     * {@link JdkClientHttpRequestFactory} (backed by {@code java.net.http.HttpClient}).
     *
     * <p>Rationale: {@code HttpURLConnection} rejects the {@code PATCH} verb with
     * {@code ProtocolException: Invalid HTTP method: PATCH}. The BFF has neither Apache
     * httpclient5 nor OkHttp on the classpath, so without this customizer Spring falls back
     * to {@code HttpURLConnection} and every outbound {@code PATCH} fails at runtime — which
     * breaks the entire partner draft-save flow ({@code RestConfigRegistryClient}'s
     * {@code patchDraftStep1..8} calls to config-registry, admin-ui → BFF → config-registry).
     * The JDK {@code HttpClient} supports arbitrary methods including PATCH. Applies to every
     * adapter that autowires the shared builder (currently {@link RestConfigRegistryClient}).
     *
     * <p>Mirrors {@code payment-executor}'s {@code ClientBeans.patchCapableRequestFactoryCustomizer()},
     * where the identical fault was proven live and fixed on the transaction-mgmt
     * {@code PATCH /v1/transactions/{ref}/status} path.
     */
    @Bean
    public RestClientCustomizer patchCapableRequestFactoryCustomizer() {
        return builder -> builder.requestFactory(new JdkClientHttpRequestFactory());
    }
}
