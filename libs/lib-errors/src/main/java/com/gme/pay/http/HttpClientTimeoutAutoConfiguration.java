package com.gme.pay.http;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

/**
 * Puts a <b>read timeout on every outbound HTTP client in the fleet</b> (gap <b>T3-11</b> defect 1,
 * evidence in {@code RUNBOOK_LOAD_AND_CAPACITY.md} §4.1 #7).
 *
 * <h2>The defect this closes</h2>
 *
 * <p>Before this, no inter-service client in the platform had a read timeout. Every
 * {@code Rest*Client} took Spring Boot's shared {@code RestClient.Builder}, which carries none, so a
 * peer that accepted a connection and then stopped answering held the caller's request thread until
 * the OS closed the socket. On the money path that is not merely slow: payment-executor's scheme
 * client <em>does</em> give up (5 s), so an adapter that hangs past it turns a payment whose outcome
 * is genuinely unknown into an {@code UNCERTAIN} row a human has to resolve. Load became manual ops
 * work.
 *
 * <h2>Why it is a default rather than a per-service edit</h2>
 *
 * <p>Twenty services, ~30 client classes. A per-class fix is a fix that is 95% applied; the one hop
 * nobody remembered is the one that hangs. Riding along in lib-errors — the module every service
 * already depends on, and where the trace, RBAC, correlation and outbox-lag auto-configurations
 * already live — makes "unbounded" impossible to reach by omission. A hop that needs something other
 * than the default states so explicitly, and the explicit statement is reviewable.
 *
 * <h2>Ordering is load-bearing</h2>
 *
 * <p>{@link Ordered#LOWEST_PRECEDENCE}, so this customizer runs <b>last</b> and its request factory
 * wins over any earlier one. Two services (payment-executor, ops-partner-bff) already register a
 * customizer that installs a bare {@code JdkClientHttpRequestFactory} to get PATCH support; if this
 * one ran first, theirs would replace it and silently remove the timeouts again. Running last means
 * the floor cannot be un-set by accident. It also means a hop that genuinely needs a different bound
 * must set it on <em>its own</em> builder ({@code .requestFactory(...)} after the shared builder is
 * configured) — which is what {@code RestSchemeClient} already does, and which is visible at the
 * call site instead of hidden in bean ordering.
 *
 * <p>Interceptor-registering customizers (correlation id, trace) are unaffected: they add to a
 * different part of the builder.
 *
 * <h2>The numbers</h2>
 *
 * <p>See {@link HttpClientTimeoutProperties}. These are engineering parameters — a floor that stops
 * "unbounded", not a service-level objective. Nothing here is a commitment to a partner; both values
 * are properties precisely so a hop with a real, owned latency budget can declare it.
 */
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(prefix = "gmepay.http.client", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HttpClientTimeoutProperties.class)
public class HttpClientTimeoutAutoConfiguration {

    /**
     * Implements {@link Ordered} on the instance rather than relying on {@code @Order} on the factory
     * method: {@code RestClientAutoConfiguration} consumes these through
     * {@code ObjectProvider.orderedStream()}, and an instance that declares its own order is honoured
     * by every ordering path, including ones that never see the factory method's annotations.
     */
    @Bean
    public RestClientCustomizer gmepayHttpClientTimeoutCustomizer(HttpClientTimeoutProperties properties) {
        return new TimeoutRestClientCustomizer(properties);
    }

    static final class TimeoutRestClientCustomizer implements RestClientCustomizer, Ordered {

        private final HttpClientTimeoutProperties properties;

        TimeoutRestClientCustomizer(HttpClientTimeoutProperties properties) {
            this.properties = properties;
        }

        @Override
        public void customize(RestClient.Builder builder) {
            builder.requestFactory(HttpClientTimeouts.requestFactory(
                    properties.getConnectTimeout(), properties.getReadTimeout()));
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
