package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.partner.PartnerCredentialService;
import com.gme.pay.gateway.partner.PartnerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * GlobalFilter — HMAC-SHA256 request signature verification (API-05 §3.2).
 *
 * <p>Execution order: 4 (after TimestampValidationFilter=3, before ReplayProtectionFilter=5).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Resolve partner by {@code X-API-Key} — 401 INVALID_API_KEY if unknown.</li>
 *   <li>Buffer request body via {@link DataBufferUtils#join}.</li>
 *   <li>Build canonical string and compute HMAC-SHA256 with the partner secret.</li>
 *   <li>Compare with {@code X-Signature} using {@link java.security.MessageDigest#isEqual}.</li>
 *   <li>On success, store {@code partner_id} and {@code partner_credentials} as exchange
 *       attributes for downstream filters (rate-limit key resolver, replay filter).</li>
 * </ol>
 */
@Component
public class HmacSignatureFilter implements GlobalFilter, Ordered {

    public static final String ATTR_PARTNER_ID    = "partner_id";
    public static final String ATTR_PARTNER_CREDS = "partner_credentials";

    /** Filter execution order position (higher number = later in chain). */
    public static final int ORDER = 4;

    private static final Logger log = LoggerFactory.getLogger(HmacSignatureFilter.class);

    private final PartnerCredentialService credentialService;

    public HmacSignatureFilter(PartnerCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String apiKey    = request.getHeaders().getFirst("X-API-Key");
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String signature = request.getHeaders().getFirst("X-Signature");

        if (apiKey == null || apiKey.isBlank()) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY",
                    "X-API-Key header is missing or empty");
        }

        return credentialService.findByApiKey(apiKey)
                .switchIfEmpty(Mono.defer(() ->
                        GatewayErrorWriter.writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY",
                                "Unknown or revoked API key")
                                .then(Mono.empty())))
                .flatMap(creds -> verifySignatureAndDelegate(
                        exchange, chain, creds, timestamp, signature));
    }

    private Mono<Void> verifySignatureAndDelegate(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            PartnerCredentials creds,
            String timestamp,
            String signature) {

        ServerHttpRequest request = exchange.getRequest();
        String method        = request.getMethod().name();
        String pathWithQuery = request.getURI().getRawPath()
                + (request.getURI().getRawQuery() != null
                   ? "?" + request.getURI().getRawQuery()
                   : "");

        // Buffer the body so we can (a) compute the hash and (b) re-expose it downstream.
        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(bodyBuffer -> {
                    byte[] bodyBytes = new byte[bodyBuffer.readableByteCount()];
                    bodyBuffer.read(bodyBytes);
                    DataBufferUtils.release(bodyBuffer);

                    String canonical = HmacSignatureVerifier.buildCanonicalString(
                            method, pathWithQuery, timestamp != null ? timestamp : "", bodyBytes);
                    String expected  = HmacSignatureVerifier.computeHmac(
                            creds.apiSecretHmacKey(), canonical);

                    if (!HmacSignatureVerifier.verifySignature(expected, signature)) {
                        log.warn("HMAC signature mismatch for partner {}", creds.partnerId());
                        return GatewayErrorWriter.writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                                "Request signature does not match");
                    }

                    // Store partner context for downstream filters
                    exchange.getAttributes().put(ATTR_PARTNER_ID, creds.partnerId());
                    exchange.getAttributes().put(ATTR_PARTNER_CREDS, creds);

                    // Re-wrap body so downstream gateway filters can still read it
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(
                                    exchange.getResponse().bufferFactory().wrap(bodyBytes));
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }
}
