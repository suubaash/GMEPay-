package com.gme.pay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Spring Cloud Gateway route table for the GMEPay+ {@code /v1/*} partner API surface.
 *
 * <p>Each route declares the HTTP methods it accepts, the path pattern it matches, and the
 * downstream service it proxies to. Downstream base URIs are read from
 * {@code gmepay.<service>.base-url} properties so they can be overridden per environment
 * (docker-compose, K8s, local dev). The defaults assume the in-cluster DNS name
 * {@code http://<service>:8080}.
 *
 * <p>Cross-cutting concerns are NOT declared per-route here:
 * <ul>
 *   <li><b>HMAC signature verification</b> and <b>idempotency-key enforcement</b> are wired as
 *       Spring Cloud Gateway {@code GlobalFilter} {@code @Component}s
 *       ({@code HmacSignatureFilter}, {@code IdempotencyKeyFilter}) — they execute on every
 *       request regardless of the route.</li>
 *   <li>The {@code X-Gateway-Version: v1} header is added by the global
 *       {@code default-filters} block in {@code application.yml}.</li>
 *   <li>Each route strips the {@code /v1} prefix via {@code RewritePath} so downstream
 *       services see their own paths (e.g. {@code /payments/cpm/generate}).</li>
 * </ul>
 *
 * <p>Per-route rate limiters, circuit breakers, and timeouts are intentionally deferred to
 * separate tickets (T07, T20) once the Redis and Resilience4j beans are wired in.
 *
 * <p><b>Route IDs.</b> Each route id is stable and used by tests/observability to refer to a
 * specific backend mapping. Service-level routes use the service name; where one service
 * exposes two distinct path families (revenue-ledger does), the suffix names the family.
 */
@Configuration
public class GatewayRoutingConfig {

    /** Common rewrite that strips the {@code /v1} prefix from forwarded requests. */
    private static final String REWRITE_REGEX       = "/v1/(?<segment>.*)";
    private static final String REWRITE_REPLACEMENT = "/${segment}";

    private final String paymentExecutorUri;
    private final String rateFxUri;
    private final String prefundingUri;
    private final String smartRouterUri;
    private final String merchantQrDataUri;
    private final String configRegistryUri;
    private final String transactionMgmtUri;
    private final String revenueLedgerUri;
    private final String settlementReconciliationUri;
    private final String reportingComplianceUri;
    private final String qrServiceUri;

    public GatewayRoutingConfig(
            @Value("${gmepay.payment-executor.base-url:http://payment-executor:8080}")
            String paymentExecutorUri,
            @Value("${gmepay.rate-fx.base-url:http://rate-fx:8080}")
            String rateFxUri,
            @Value("${gmepay.prefunding.base-url:http://prefunding:8080}")
            String prefundingUri,
            @Value("${gmepay.smart-router.base-url:http://smart-router:8080}")
            String smartRouterUri,
            @Value("${gmepay.merchant-qr-data.base-url:http://merchant-qr-data:8080}")
            String merchantQrDataUri,
            @Value("${gmepay.config-registry.base-url:http://config-registry:8080}")
            String configRegistryUri,
            @Value("${gmepay.transaction-mgmt.base-url:http://transaction-mgmt:8080}")
            String transactionMgmtUri,
            @Value("${gmepay.revenue-ledger.base-url:http://revenue-ledger:8080}")
            String revenueLedgerUri,
            @Value("${gmepay.settlement-reconciliation.base-url:http://settlement-reconciliation:8080}")
            String settlementReconciliationUri,
            @Value("${gmepay.reporting-compliance.base-url:http://reporting-compliance:8080}")
            String reportingComplianceUri,
            @Value("${gmepay.qr-service.base-url:http://qr-service:8080}")
            String qrServiceUri) {
        this.paymentExecutorUri          = paymentExecutorUri;
        this.rateFxUri                   = rateFxUri;
        this.prefundingUri               = prefundingUri;
        this.smartRouterUri              = smartRouterUri;
        this.merchantQrDataUri           = merchantQrDataUri;
        this.configRegistryUri           = configRegistryUri;
        this.transactionMgmtUri          = transactionMgmtUri;
        this.revenueLedgerUri            = revenueLedgerUri;
        this.settlementReconciliationUri = settlementReconciliationUri;
        this.reportingComplianceUri      = reportingComplianceUri;
        this.qrServiceUri                = qrServiceUri;
    }

    @Bean
    public RouteLocator v1Routes(RouteLocatorBuilder builder) {
        return builder.routes()

                // ----- payment-executor : POST /v1/payments/** -----
                .route("payment-executor", r -> r
                        .path("/v1/payments/**")
                        .and().method(HttpMethod.POST)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(paymentExecutorUri))

                // ----- rate-fx : POST /v1/rates -----
                .route("rate-fx", r -> r
                        .path("/v1/rates")
                        .and().method(HttpMethod.POST)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(rateFxUri))

                // ----- rate-fx quotes : POST /v1/quotes/** (partner-priced issue) + GET retrieve -----
                .route("rate-fx-quotes", r -> r
                        .path("/v1/quotes/**")
                        .and().method(HttpMethod.POST, HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(rateFxUri))

                // ----- prefunding : GET + POST /v1/prefunding/** -----
                .route("prefunding", r -> r
                        .path("/v1/prefunding/**")
                        .and().method(HttpMethod.GET, HttpMethod.POST)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(prefundingUri))

                // ----- smart-router : GET /v1/route -----
                .route("smart-router", r -> r
                        .path("/v1/route")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(smartRouterUri))

                // ----- merchant-qr-data : GET /v1/merchants/** -----
                .route("merchant-qr-data", r -> r
                        .path("/v1/merchants/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(merchantQrDataUri))

                // ----- config-registry : GET + PUT /v1/partners/** -----
                .route("config-registry", r -> r
                        .path("/v1/partners/**")
                        .and().method(HttpMethod.GET, HttpMethod.PUT)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(configRegistryUri))

                // ----- transaction-mgmt : GET + POST + PATCH /v1/transactions/** -----
                .route("transaction-mgmt", r -> r
                        .path("/v1/transactions/**")
                        .and().method(HttpMethod.GET, HttpMethod.POST, HttpMethod.PATCH)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(transactionMgmtUri))

                // ----- revenue-ledger : GET /v1/revenue/** -----
                .route("revenue-ledger-revenue", r -> r
                        .path("/v1/revenue/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(revenueLedgerUri))

                // ----- revenue-ledger : POST /v1/journals/** -----
                .route("revenue-ledger-journals", r -> r
                        .path("/v1/journals/**")
                        .and().method(HttpMethod.POST)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(revenueLedgerUri))

                // ----- settlement-reconciliation : GET /v1/settlements/** -----
                .route("settlement-reconciliation", r -> r
                        .path("/v1/settlements/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(settlementReconciliationUri))

                // ----- reporting-compliance : GET /v1/reports/** -----
                .route("reporting-compliance", r -> r
                        .path("/v1/reports/**")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(reportingComplianceUri))

                // ----- qr-service : GET + POST /v1/qr/** -----
                .route("qr-service", r -> r
                        .path("/v1/qr/**")
                        .and().method(HttpMethod.GET, HttpMethod.POST)
                        .filters(f -> f.rewritePath(REWRITE_REGEX, REWRITE_REPLACEMENT))
                        .uri(qrServiceUri))

                .build();
    }
}
