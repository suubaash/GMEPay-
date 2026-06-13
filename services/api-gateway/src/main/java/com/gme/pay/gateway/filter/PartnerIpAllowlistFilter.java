package com.gme.pay.gateway.filter;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.HashChain;
import com.gme.pay.domain.net.Cidr;
import com.gme.pay.gateway.partner.PartnerCredentialService;
import com.gme.pay.gateway.registry.IpAllowlistCache;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * GlobalFilter — partner IP-allowlist enforcement (Slice 8, defense-in-depth).
 *
 * <p>Execution order: 2 — BEFORE TimestampValidationFilter(3) and
 * {@link HmacSignatureFilter}(4). Deliberate: a request from a source address outside the
 * partner's registered ranges must be rejected before ANY of the signing surface
 * (timestamp/signature parsing, credential resolution for verification) is exercised.
 *
 * <p>Scope: only partner machine traffic — requests bearing {@code X-Partner-Id} and/or
 * {@code X-API-Key}. JWT-bearing human traffic (admin-ui / partner-portal-ui via the BFF)
 * carries neither header and passes through untouched; it is authenticated by the
 * Spring Security resource-server chain instead.
 *
 * <h2>SECURITY — partner identity resolution (read before changing)</h2>
 *
 * <p>This filter runs BEFORE HMAC verification, so at decision time the only identity
 * signals available are unauthenticated headers. {@code X-Partner-Id} (the partner-portal-ui
 * contract header) is attacker-controlled: trusting it alone would let an attacker have
 * their source IP checked against a DIFFERENT partner's allowlist (pick any partner with a
 * broad allowlist and you are through). Two modes, switched by
 * {@code security.gateway.allowlist.trust_header_only_in_dev}:
 *
 * <ul>
 *   <li>{@code true} (DEV ONLY — default outside the {@code prod} profile): the bare
 *       {@code X-Partner-Id} header is trusted as the partner identity. Convenient for
 *       local development and tests; unsafe at the real edge.</li>
 *   <li>{@code false} (the {@code prod} profile default): the partner is resolved from the
 *       HMAC keyId ({@code X-API-Key} via {@link PartnerCredentialService} — the same
 *       lookup {@link HmacSignatureFilter} performs). A present-but-mismatching
 *       {@code X-Partner-Id} is rejected with 403 {@code PARTNER_ID_MISMATCH} so a spoofed
 *       header can never select another partner's allowlist.</li>
 * </ul>
 *
 * <p>TODO(Slice 8 hardening): once credential issuance lands, fold this check into a single
 * pre-resolution step shared with {@link HmacSignatureFilter} so the keyId→partner lookup
 * happens exactly once per request, then delete the dev trust mode entirely.
 *
 * <h2>Decision table</h2>
 * <ol>
 *   <li>Environment = {@code X-Environment} header ({@code sandbox|production}, case
 *       insensitive), defaulting to {@code security.gateway.allowlist.default-environment}
 *       ({@code sandbox} in dev). Any other value → 400 VALIDATION_ERROR.</li>
 *   <li>Source IP = first X-Forwarded-For hop that parses and is not an internal/private
 *       range ({@link Cidr#isInternal}), else the socket remote address.</li>
 *   <li>Allowlist = config-registry rows for (partner, environment) via
 *       {@link IpAllowlistCache} (TTL ≤ 60 s). Empty list → 403 (fail closed: an
 *       unconfigured environment admits nobody). Lookup failure → the
 *       {@code security.gateway.allowlist.fail-open} flag decides (mirrors
 *       {@code gateway.replay-protection.fail-open}); HMAC verification still stands
 *       between a failed-open request and any downstream service.</li>
 *   <li>No CIDR match → 403 {@code IP_NOT_ALLOWED} + an ADR-007 audit event
 *       ({@code GATEWAY_IP_REJECTED}) through the {@link AuditPublisher} path.</li>
 * </ol>
 */
@Component
public class PartnerIpAllowlistFilter implements GlobalFilter, Ordered {

    /** Filter execution order — before TimestampValidationFilter(3) / HmacSignatureFilter(4). */
    public static final int ORDER = 2;

    public static final String HEADER_PARTNER_ID = "X-Partner-Id";
    public static final String HEADER_ENVIRONMENT = "X-Environment";
    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    public static final String CODE_IP_NOT_ALLOWED = "IP_NOT_ALLOWED";
    public static final String CODE_PARTNER_ID_MISMATCH = "PARTNER_ID_MISMATCH";

    /** ADR-007 audit discriminators for edge rejections. */
    public static final String AUDIT_AGGREGATE_TYPE = "partner_ip_allowlist";
    public static final String AUDIT_EVENT_IP_REJECTED = "GATEWAY_IP_REJECTED";
    public static final String AUDIT_EVENT_SPOOFED_PARTNER = "GATEWAY_PARTNER_ID_SPOOF_REJECTED";

    private static final String ENV_SANDBOX = "SANDBOX";
    private static final String ENV_PRODUCTION = "PRODUCTION";

    private static final Logger log = LoggerFactory.getLogger(PartnerIpAllowlistFilter.class);

    /** Sentinel: partner identity not resolvable pre-HMAC — defer to the HMAC filter's 401. */
    private static final Identity UNRESOLVED = new Identity(null, null);

    private record Identity(String partnerCode, String spoofedHeader) {
        boolean unresolved() {
            return partnerCode == null;
        }

        boolean spoofed() {
            return spoofedHeader != null;
        }
    }

    private final IpAllowlistCache allowlistCache;
    private final PartnerCredentialService credentialService;
    private final ObjectProvider<AuditPublisher> auditPublisherProvider;
    private final boolean trustHeaderOnlyInDev;
    private final String defaultEnvironment;
    private final boolean failOpen;

    public PartnerIpAllowlistFilter(
            IpAllowlistCache allowlistCache,
            PartnerCredentialService credentialService,
            ObjectProvider<AuditPublisher> auditPublisherProvider,
            @Value("${security.gateway.allowlist.trust_header_only_in_dev:true}")
            boolean trustHeaderOnlyInDev,
            @Value("${security.gateway.allowlist.default-environment:sandbox}")
            String defaultEnvironment,
            @Value("${security.gateway.allowlist.fail-open:true}") boolean failOpen) {
        this.allowlistCache = allowlistCache;
        this.credentialService = credentialService;
        this.auditPublisherProvider = auditPublisherProvider;
        this.trustHeaderOnlyInDev = trustHeaderOnlyInDev;
        this.defaultEnvironment = defaultEnvironment;
        this.failOpen = failOpen;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String partnerHeader = trimToNull(headers.getFirst(HEADER_PARTNER_ID));
        String apiKey = trimToNull(headers.getFirst(HEADER_API_KEY));

        // Not partner machine traffic (no HMAC markers): JWT traffic — out of scope.
        if (partnerHeader == null && apiKey == null) {
            return chain.filter(exchange);
        }

        String environment = resolveEnvironment(headers.getFirst(HEADER_ENVIRONMENT));
        if (environment == null) {
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    HEADER_ENVIRONMENT + " must be one of sandbox|production");
        }

        String sourceIp = resolveSourceIp(exchange.getRequest());

        return resolveIdentity(partnerHeader, apiKey)
                .flatMap(identity -> {
                    if (identity.unresolved()) {
                        // No verifiable partner identity pre-HMAC (missing/unknown API key).
                        // HmacSignatureFilter 401s these without touching any signature,
                        // so no signing surface is exposed by deferring.
                        return chain.filter(exchange);
                    }
                    if (identity.spoofed()) {
                        audit(AUDIT_EVENT_SPOOFED_PARTNER, identity.partnerCode(), environment,
                                sourceIp, "X-Partner-Id header '" + identity.spoofedHeader()
                                        + "' does not match the API key's partner");
                        return GatewayErrorWriter.writeError(
                                exchange, HttpStatus.FORBIDDEN, CODE_PARTNER_ID_MISMATCH,
                                "X-Partner-Id does not match the authenticated API key");
                    }
                    return enforceAllowlist(
                            exchange, chain, identity.partnerCode(), environment, sourceIp);
                });
    }

    // ----------------------- identity resolution ----------------------------

    private Mono<Identity> resolveIdentity(String partnerHeader, String apiKey) {
        if (trustHeaderOnlyInDev) {
            // DEV ONLY: trust the bare header. See class javadoc SECURITY note.
            if (partnerHeader != null) {
                return Mono.just(new Identity(partnerHeader, null));
            }
            return lookupByApiKey(apiKey, null);
        }
        // Prod mode: identity is the HMAC keyId's partner; the header may only confirm it.
        return lookupByApiKey(apiKey, partnerHeader);
    }

    private Mono<Identity> lookupByApiKey(String apiKey, String partnerHeader) {
        if (apiKey == null) {
            return Mono.just(UNRESOLVED);
        }
        return credentialService.findByApiKey(apiKey)
                .map(creds -> {
                    if (partnerHeader != null && !partnerHeader.equals(creds.partnerId())) {
                        return new Identity(creds.partnerId(), partnerHeader);
                    }
                    return new Identity(creds.partnerId(), null);
                })
                .defaultIfEmpty(UNRESOLVED);
    }

    // ----------------------- allowlist enforcement --------------------------

    /** Lookup outcome, materialised so the fail-open error handling cannot leak into the chain. */
    private enum Verdict { ALLOW, ALLOW_FAIL_OPEN, DENY, DENY_LOOKUP_FAILED }

    private Mono<Void> enforceAllowlist(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String partnerCode,
            String environment,
            String sourceIp) {

        if (sourceIp == null) {
            audit(AUDIT_EVENT_IP_REJECTED, partnerCode, environment, null,
                    "source address could not be determined");
            return GatewayErrorWriter.writeError(
                    exchange, HttpStatus.FORBIDDEN, CODE_IP_NOT_ALLOWED,
                    "Source IP could not be determined; request rejected");
        }

        return allowlistCache.getCidrs(partnerCode, environment)
                .map(cidrs -> isAllowed(sourceIp, cidrs) ? Verdict.ALLOW : Verdict.DENY)
                .onErrorResume(lookupFailure -> {
                    log.error("IP allowlist lookup failed for partner={} env={}: {}",
                            partnerCode, environment, lookupFailure.toString());
                    return Mono.just(failOpen ? Verdict.ALLOW_FAIL_OPEN
                                             : Verdict.DENY_LOOKUP_FAILED);
                })
                .flatMap(verdict -> switch (verdict) {
                    case ALLOW -> chain.filter(exchange);
                    case ALLOW_FAIL_OPEN -> {
                        log.warn("allowlist fail-open: admitting partner={} ip={} env={} "
                                + "(HMAC verification still applies)",
                                partnerCode, sourceIp, environment);
                        yield chain.filter(exchange);
                    }
                    case DENY, DENY_LOOKUP_FAILED -> {
                        audit(AUDIT_EVENT_IP_REJECTED, partnerCode, environment, sourceIp,
                                verdict == Verdict.DENY_LOOKUP_FAILED
                                        ? "allowlist lookup failed and fail-open is disabled"
                                        : "source IP not in allowlist");
                        yield GatewayErrorWriter.writeError(
                                exchange, HttpStatus.FORBIDDEN, CODE_IP_NOT_ALLOWED,
                                "Source IP " + sourceIp + " not in partner allowlist for "
                                        + environment.toLowerCase(Locale.ROOT));
                    }
                });
    }

    private static boolean isAllowed(String sourceIp, List<String> cidrs) {
        // Empty allowlist fails closed: an environment with no registered ranges admits nobody.
        return cidrs.stream().anyMatch(cidr -> Cidr.matches(sourceIp, cidr));
    }

    // ----------------------- request attribute extraction -------------------

    /**
     * {@code null} when the header is absent or blank — both fall back to
     * {@code security.gateway.allowlist.default-environment}; an unrecognised value
     * returns {@code null} from THIS method only via the roster check below.
     */
    private String resolveEnvironment(String rawHeader) {
        String value = rawHeader == null || rawHeader.isBlank()
                ? defaultEnvironment
                : rawHeader.trim();
        String canonical = value.toUpperCase(Locale.ROOT);
        if (ENV_SANDBOX.equals(canonical) || ENV_PRODUCTION.equals(canonical)) {
            return canonical; // V026 spelling
        }
        return null;
    }

    /**
     * The originating client address: first X-Forwarded-For hop that parses as an IP
     * literal and is not an internal/private range (LBs and reverse proxies on our side
     * of the edge append themselves as internal hops), falling back to the socket
     * remote address when the header is absent or carries internal hops only.
     */
    private static String resolveSourceIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst(HEADER_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String hop : forwardedFor.split(",")) {
                String candidate = hop.trim();
                if (!candidate.isEmpty() && Cidr.isValidAddress(candidate)
                        && !Cidr.isInternal(candidate)) {
                    return candidate;
                }
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        String host = remote.getAddress().getHostAddress();
        int scope = host.indexOf('%'); // strip IPv6 zone id ("fe80::1%eth0")
        return scope >= 0 ? host.substring(0, scope) : host;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ----------------------- audit --------------------------------------------

    /**
     * ADR-007 audit event for an edge rejection, published through the lib-audit
     * {@link AuditPublisher} path (LogAuditPublisher by default, Kafka when the audit
     * sink lands). Edge rejections have no DB row to chain onto, so each event chains
     * from {@link HashChain#GENESIS} — the durable per-aggregate chain lives with the
     * audit sink, not the gateway.
     */
    private void audit(String eventType, String partnerCode, String environment,
                       String sourceIp, String reason) {
        try {
            String afterJson = "{\"environment\":\"" + environment
                    + "\",\"sourceIp\":\"" + (sourceIp == null ? "unknown" : sourceIp)
                    + "\",\"reason\":\"" + reason + "\"}";
            AuditEvent event = AuditEvent.newEvent(
                    AUDIT_AGGREGATE_TYPE,
                    partnerCode == null ? "unknown" : partnerCode,
                    "system",
                    sourceIp,
                    eventType,
                    null,
                    afterJson.getBytes(StandardCharsets.UTF_8),
                    HashChain.GENESIS,
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
            AuditPublisher publisher = auditPublisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.publish(event);
            } else {
                log.warn("no AuditPublisher wired; {} partner={} ip={} env={} reason={}",
                        eventType, partnerCode, sourceIp, environment, reason);
            }
        } catch (RuntimeException auditFailure) {
            // Audit must never turn a clean 403 into a 500 at the edge.
            log.error("failed to publish {} audit event for partner={}: {}",
                    eventType, partnerCode, auditFailure.toString());
        }
    }
}
