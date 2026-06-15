package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.partner.PartnerCredentialService;
import com.gme.pay.gateway.partner.PartnerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * GlobalFilter — HMAC-SHA256 request signature verification (API-05 §3.2).
 *
 * <p>Execution order: 4 (after PartnerIpAllowlistFilter=2, before ReplayProtectionFilter=5).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Validate {@code X-Timestamp} is present, parses as ISO-8601, and falls within the
 *       configured clock-skew window ({@code security.gateway.hmac.clock-skew-seconds},
 *       default 300 s = 5 min). Reject stale/future timestamps with 401 EXPIRED_TIMESTAMP.</li>
 *   <li>Resolve partner by {@code X-API-Key} — 401 INVALID_API_KEY if unknown.</li>
 *   <li>Buffer request body via {@link DataBufferUtils#join}.</li>
 *   <li>Build canonical string and compute HMAC-SHA256 with the partner secret.</li>
 *   <li>Compare with {@code X-Signature} using {@link java.security.MessageDigest#isEqual}.</li>
 *   <li>On success, store {@code partner_id} and {@code partner_credentials} as exchange
 *       attributes for downstream filters (rate-limit key resolver, replay filter).</li>
 * </ol>
 *
 * <p><b>Clock-skew window</b>: the {@code X-Timestamp} value is the UTC ISO-8601 string the
 * partner puts in the canonical string (e.g. {@code 2026-06-15T12:00:00.000Z}). This filter
 * rejects requests whose timestamp differs from server wall-clock by more than
 * {@code security.gateway.hmac.clock-skew-seconds} in either direction, guarding against
 * replay attacks. The {@code Clock} is injectable so tests can pin a deterministic instant.
 */
@Component
public class HmacSignatureFilter implements GlobalFilter, Ordered {

    public static final String ATTR_PARTNER_ID    = "partner_id";
    public static final String ATTR_PARTNER_CREDS = "partner_credentials";

    /** Filter execution order position (higher number = later in chain). */
    public static final int ORDER = 4;

    /** Default clock-skew tolerance: 5 minutes in either direction. */
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 300L;

    private static final Logger log = LoggerFactory.getLogger(HmacSignatureFilter.class);

    private final PartnerCredentialService credentialService;
    private final Clock clock;
    private final Duration clockSkew;

    /** Primary constructor — Spring wires this one. Clock is wall-clock UTC. */
    @org.springframework.beans.factory.annotation.Autowired
    public HmacSignatureFilter(
            PartnerCredentialService credentialService,
            @Value("${security.gateway.hmac.clock-skew-seconds:" + DEFAULT_CLOCK_SKEW_SECONDS + "}")
            long clockSkewSeconds) {
        this(credentialService, Clock.systemUTC(), clockSkewSeconds);
    }

    /** Package-private constructor for tests to inject a pinned clock. */
    HmacSignatureFilter(PartnerCredentialService credentialService, Clock clock, long clockSkewSeconds) {
        this.credentialService = credentialService;
        this.clock = clock;
        this.clockSkew = Duration.ofSeconds(clockSkewSeconds);
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

        // Validate timestamp before touching credentials (no signing surface exposure).
        Mono<Void> timestampError = validateTimestamp(exchange, timestamp);
        if (timestampError != null) {
            return timestampError;
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

    /**
     * Returns a non-null {@link Mono} (the 401 error response) when the timestamp is missing,
     * unparseable, or outside the clock-skew window; {@code null} means the timestamp is valid
     * and processing should continue.
     */
    private Mono<Void> validateTimestamp(ServerWebExchange exchange, String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.UNAUTHORIZED, "EXPIRED_TIMESTAMP",
                    "X-Timestamp header is missing or empty");
        }
        Instant requestTime;
        try {
            requestTime = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.UNAUTHORIZED, "EXPIRED_TIMESTAMP",
                    "X-Timestamp is not a valid ISO-8601 UTC instant");
        }
        Instant now = clock.instant();
        Duration delta = Duration.between(requestTime, now).abs();
        if (delta.compareTo(clockSkew) > 0) {
            log.warn("HMAC timestamp outside clock-skew window: ts={} now={} delta={}s",
                    timestamp, now, delta.toSeconds());
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.UNAUTHORIZED, "EXPIRED_TIMESTAMP",
                    "X-Timestamp is outside the permitted clock-skew window of "
                            + clockSkew.toSeconds() + " seconds");
        }
        return null; // timestamp valid
    }

    private Mono<Void> verifySignatureAndDelegate(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            PartnerCredentials creds,
            String timestamp,
            String signature) {

        ServerHttpRequest request = exchange.getRequest();
        String method        = request.getMethod().name();
        // The partner signs the request line it SENT — i.e. the original as-received
        // URI, before any gateway RewritePath rewrites it for the downstream proxy.
        // Validating against the post-rewrite path would reject every real partner.
        URI signedUri        = originalRequestUri(exchange, request);
        String pathWithQuery = signedUri.getRawPath()
                + (signedUri.getRawQuery() != null
                   ? "?" + signedUri.getRawQuery()
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

    /**
     * Resolve the original, as-received request URI — the one the partner signed —
     * independent of any gateway {@code RewritePath} that may have already mutated the
     * request path for the downstream proxy. Spring Cloud Gateway records each pre-rewrite
     * URL in {@link ServerWebExchangeUtils#GATEWAY_ORIGINAL_REQUEST_URL_ATTR} (an
     * insertion-ordered set); the first entry is the truly-original URI. If no rewrite has
     * run yet (this filter ordered ahead of RewritePath), the current request URI already
     * IS the original, so we fall back to it.
     */
    private static URI originalRequestUri(ServerWebExchange exchange, ServerHttpRequest request) {
        Object attr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (attr instanceof Set<?> urls && !urls.isEmpty()) {
            Object first = urls.iterator().next();
            if (first instanceof URI uri) {
                return uri;
            }
        }
        return request.getURI();
    }
}
