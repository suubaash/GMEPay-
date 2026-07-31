package com.gme.pay.scheme.ninepay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rejects {@code POST /scheme/ipn} from any source outside the configured allowlist —
 * gap <b>T5-7</b>, the compensating control for the unfixable <b>T5-6</b>.
 *
 * <p>Runs <em>before</em> the controller and before the request body is bound, so a forged
 * IPN from an unlisted source never reaches {@code NinepaySchemeAdapter.handleIpn} and never
 * lands in {@code np_ipn_events}. Only the IPN path is filtered; the hub-facing
 * {@code /scheme/payout|balance|decode-qr} endpoints are internal and are gated by the
 * platform's own internal-auth, not by 9Pay's egress ranges.</p>
 *
 * <p><b>The primary control is the ingress</b>
 * ({@code deploy/helm/gmepay/templates/ingress-ipn.yaml}) — a packet dropped at the edge
 * costs nothing, while this filter has already accepted a TCP connection. This layer exists
 * because the adapter is reachable directly in compose, from inside the cluster, and in any
 * deployment where someone port-forwards or exposes the Service.</p>
 *
 * <p>See {@link IpnSourceAllowlist} for the two explicit states (configured ⇒ fail closed;
 * unconfigured ⇒ admit + WARN, so local/dev is not broken) and for why a malformed range is
 * a startup failure rather than a third state.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class NinepayIpnSourceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NinepayIpnSourceFilter.class);

    /** The single inbound edge 9Pay pushes to. */
    static final String IPN_PATH = "/scheme/ipn";

    private final IpnSourceAllowlist allowlist;
    private final int trustedProxyCount;
    private final ObjectMapper mapper;
    /** Ensures the "no allowlist configured" fact is stated on the first real IPN too, once. */
    private final AtomicBoolean unguardedIpnLogged = new AtomicBoolean(false);

    public NinepayIpnSourceFilter(
            @Value("${gmepay.scheme.ninepay.ipn.allowed-source-ranges:}") String allowedSourceRanges,
            @Value("${gmepay.scheme.ninepay.ipn.trusted-proxy-count:0}") int trustedProxyCount,
            ObjectMapper mapper) {
        // Throws on a malformed range -> the context fails to start. Deliberate: booting with a
        // typo'd CIDR gives an operator who believes the edge is protected an edge that is not.
        this.allowlist = IpnSourceAllowlist.parse(allowedSourceRanges);
        this.trustedProxyCount = Math.max(0, trustedProxyCount);
        this.mapper = mapper;
        logState();
    }

    private void logState() {
        if (allowlist.isConfigured()) {
            log.info("9Pay IPN source allowlist ENFORCING (T5-7): {} range(s) {}, "
                            + "trusted-proxy-count={} ({}). POST {} from any other source -> 403.",
                    allowlist.size(), allowlist.describe(), trustedProxyCount,
                    trustedProxyCount == 0
                            ? "using the socket peer address; X-Forwarded-For is IGNORED"
                            : "client taken " + trustedProxyCount + " hop(s) from the right of X-Forwarded-For",
                    IPN_PATH);
        } else {
            log.warn("9Pay IPN source allowlist NOT CONFIGURED (T5-7 INACTIVE): POST {} accepts "
                            + "ANY source IP. This allowlist is the ONLY compensating control for T5-6 "
                            + "(9Pay does not sign the IPN 'code' field, so a captured success IPN can be "
                            + "replayed as a reversal and still verifies). Acceptable for LOCAL/DEV only. "
                            + "Set GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES to the ranges 9Pay "
                            + "supplies in writing, and enforce them at the ingress as well.",
                    IPN_PATH);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !IPN_PATH.equals(pathWithinApplication(request));
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        int query = uri.indexOf('?');
        return query >= 0 ? uri.substring(0, query) : uri;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String clientIp = IpnSourceAllowlist.resolveClientIp(
                request.getRemoteAddr(), forwardedFor, trustedProxyCount);

        if (!allowlist.isConfigured()) {
            if (unguardedIpnLogged.compareAndSet(false, true)) {
                log.warn("9Pay IPN ADMITTED from {} with NO source allowlist configured (T5-7 "
                                + "INACTIVE) — this and every later IPN is accepted from any network. "
                                + "Logged once per process; see the startup WARN.",
                        clientIp);
            } else {
                log.debug("9Pay IPN admitted from {} (allowlist not configured)", clientIp);
            }
            chain.doFilter(request, response);
            return;
        }

        if (!allowlist.permits(clientIp)) {
            log.warn("9Pay IPN REJECTED (T5-7): source {} is not in the allowlist {} "
                            + "[remoteAddr={} xff={} trustedProxyCount={}]. Body NOT parsed, nothing audited "
                            + "to np_ipn_events — a rejected source is not an IPN.",
                    clientIp, allowlist.describe(), request.getRemoteAddr(),
                    forwardedFor == null ? "-" : forwardedFor, trustedProxyCount);
            writeForbidden(response, clientIp);
            return;
        }

        log.debug("9Pay IPN source {} permitted by the T5-7 allowlist", clientIp);
        chain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response, String clientIp) throws IOException {
        // Deliberately terse: do not tell an unlisted caller what the allowlist contains, or
        // whether the request would otherwise have been a valid IPN.
        ApiError body = ApiError.of(ErrorCode.FORBIDDEN,
                "source address not permitted on the IPN edge"
                        + (clientIp == null ? " (client address could not be determined)" : ""));
        response.setStatus(ErrorCode.FORBIDDEN.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
