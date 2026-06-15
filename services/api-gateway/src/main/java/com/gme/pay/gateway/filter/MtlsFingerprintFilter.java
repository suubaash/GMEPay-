package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.partner.PartnerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GlobalFilter — mTLS client-certificate fingerprint verification (config-gated).
 *
 * <p><b>Architecture note — Nginx mTLS termination:</b> In production Nginx terminates the
 * mTLS handshake (server presents its cert, requires a client cert, verifies the CA). After a
 * successful handshake Nginx extracts the client certificate's SHA-256 fingerprint and forwards
 * it as the {@code X-Client-Cert-Fingerprint} header (lower-case hex, no colons). This filter
 * trusts that header value as the verified fingerprint; it MUST NOT be reachable except through
 * Nginx (network policy / firewall rule) because the header is attacker-controllable from the
 * public internet.
 *
 * <p><b>Execution order: 3</b> — after {@link PartnerIpAllowlistFilter} (order 2) but before
 * {@link HmacSignatureFilter} (order 4). Rationale: IP-allowlist already admitted the request;
 * we want to validate the cert binding before buffering the body for HMAC.
 *
 * <p>When {@code security.gateway.mtls.enabled=false} (the default for local dev / test), this
 * filter is a no-op and passes every request through — mTLS is enforced only in environments
 * where the property is set to {@code true}.
 *
 * <p><b>Decision table (when enabled):</b>
 * <ol>
 *   <li>If {@code X-Client-Cert-Fingerprint} is absent or blank → 401
 *       {@code MTLS_CERT_MISSING}. The request reached this filter without a client cert (or
 *       the cert header was stripped).</li>
 *   <li>If {@link HmacSignatureFilter#ATTR_PARTNER_CREDS} is already in exchange attributes
 *       (a prior filter resolved credentials) that credential object is used; otherwise the
 *       filter looks up {@link HmacSignatureFilter#ATTR_PARTNER_CREDS} from the exchange. In
 *       practice, because this filter runs BEFORE the HMAC filter, partner credentials are NOT
 *       yet in exchange attributes. The filter inspects the raw {@code X-API-Key} header and
 *       delegates the credential lookup to ensure a single consistent path. The HMAC filter
 *       still performs its own full credential lookup and signature check — this filter only
 *       validates the certificate binding.</li>
 *   <li>If the partner has no registered certificate ({@link PartnerCredentials#mtlsCertFingerprint}
 *       is {@code null}) → 401 {@code MTLS_CERT_NOT_REGISTERED}.</li>
 *   <li>If the fingerprint does not match → 401 {@code MTLS_CERT_MISMATCH}.</li>
 *   <li>Match → proceed.</li>
 * </ol>
 *
 * <p>Configuration keys:
 * <ul>
 *   <li>{@code security.gateway.mtls.enabled} (boolean, default {@code false})</li>
 *   <li>{@code security.gateway.mtls.client-cert-header} (string, default
 *       {@code X-Client-Cert-Fingerprint})</li>
 * </ul>
 */
@Component
public class MtlsFingerprintFilter implements GlobalFilter, Ordered {

    /** Execution order — after PartnerIpAllowlistFilter(2), before HmacSignatureFilter(4). */
    public static final int ORDER = 3;

    /** Header name Nginx uses to forward the verified client-cert SHA-256 fingerprint. */
    public static final String DEFAULT_CERT_HEADER = "X-Client-Cert-Fingerprint";

    private static final Logger log = LoggerFactory.getLogger(MtlsFingerprintFilter.class);

    private final boolean mtlsEnabled;
    private final String certHeader;
    private final com.gme.pay.gateway.partner.PartnerCredentialService credentialService;

    public MtlsFingerprintFilter(
            com.gme.pay.gateway.partner.PartnerCredentialService credentialService,
            @Value("${security.gateway.mtls.enabled:false}") boolean mtlsEnabled,
            @Value("${security.gateway.mtls.client-cert-header:" + DEFAULT_CERT_HEADER + "}")
            String certHeader) {
        this.credentialService = credentialService;
        this.mtlsEnabled = mtlsEnabled;
        this.certHeader  = certHeader;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!mtlsEnabled) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (apiKey == null || apiKey.isBlank()) {
            // No API key present — this is JWT/human traffic; mTLS cert check does not apply.
            return chain.filter(exchange);
        }

        String certFingerprint = exchange.getRequest().getHeaders().getFirst(certHeader);
        if (certFingerprint == null || certFingerprint.isBlank()) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.UNAUTHORIZED, "MTLS_CERT_MISSING",
                    "mTLS is required but no client certificate fingerprint was presented");
        }

        String normalised = certFingerprint.trim().toLowerCase(java.util.Locale.ROOT);

        return credentialService.findByApiKey(apiKey)
                .switchIfEmpty(Mono.defer(() ->
                        // Unknown API key — HmacSignatureFilter will 401 this; pass through.
                        chain.filter(exchange).then(Mono.empty())))
                .flatMap(creds -> {
                    if (creds.mtlsCertFingerprint() == null) {
                        log.warn("mTLS: partner {} has no registered certificate; rejecting",
                                creds.partnerId());
                        return GatewayErrorWriter.writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "MTLS_CERT_NOT_REGISTERED",
                                "Partner has no registered mTLS client certificate");
                    }
                    if (!creds.mtlsCertFingerprint().equalsIgnoreCase(normalised)) {
                        log.warn("mTLS fingerprint mismatch for partner {}; "
                                + "expected={} presented={}",
                                creds.partnerId(), creds.mtlsCertFingerprint(), normalised);
                        return GatewayErrorWriter.writeError(
                                exchange, HttpStatus.UNAUTHORIZED, "MTLS_CERT_MISMATCH",
                                "Client certificate fingerprint does not match partner record");
                    }
                    return chain.filter(exchange);
                });
    }
}
