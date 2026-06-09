package com.gme.pay.payment.client.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Small helper that registers JSR-310 (java.time) support on a {@link RestClient.Builder}
 * so {@link java.time.Instant} fields parse cleanly without per-record annotations.
 *
 * <p>Used by both the production {@code @Bean} builder in {@link ClientBeans} and by each
 * adapter's test so the wire contract stays identical between the two paths.
 */
public final class RestClientSupport {

    private RestClientSupport() {}

    /**
     * Returns the given builder with a Jackson converter that has {@link JavaTimeModule}
     * registered prepended to its message-converter chain.
     */
    public static RestClient.Builder withJavaTime(RestClient.Builder builder) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MappingJackson2HttpMessageConverter jackson = new MappingJackson2HttpMessageConverter(mapper);
        return builder.messageConverters(converters -> converters.add(0, jackson));
    }
}
