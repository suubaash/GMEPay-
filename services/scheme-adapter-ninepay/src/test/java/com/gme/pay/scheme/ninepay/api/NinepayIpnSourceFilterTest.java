package com.gme.pay.scheme.ninepay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap <b>T5-7</b> — {@link NinepayIpnSourceFilter}: the IPN edge only answers sources on the
 * allowlist, and the unconfigured state is a deliberate, visible pass-through rather than a
 * pretend-secure one.
 *
 * <p>Why this matters more than a normal auth check: <b>T5-6</b> cannot be closed in code we
 * own (9Pay does not sign the IPN {@code code} field, so a captured success IPN with
 * {@code code} rewritten to a reversal still verifies), and this filter is the only remaining
 * thing that distinguishes 9Pay from anyone who once saw a 9Pay IPN.</p>
 */
class NinepayIpnSourceFilterTest {

    private static final String CONFIGURED = "198.51.100.0/24";

    private NinepayIpnSourceFilter filter(String ranges, int trustedProxies) {
        return new NinepayIpnSourceFilter(ranges, trustedProxies, new ObjectMapper());
    }

    private static MockHttpServletRequest ipnFrom(String remoteAddr) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", NinepayIpnSourceFilter.IPN_PATH);
        req.setRemoteAddr(remoteAddr);
        return req;
    }

    // ---------------------------------------------------------------- enforcing

    @Test
    @DisplayName("configured: an allowlisted source reaches the controller")
    void allowlistedSourcePassesThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(CONFIGURED, 0).doFilter(ipnFrom("198.51.100.7"), res, chain);

        assertNotNull(chain.getRequest(), "the request must have been passed down the chain");
        assertEquals(200, res.getStatus());
    }

    @Test
    @DisplayName("configured: an unlisted source gets 403 and NEVER reaches the controller")
    void unlistedSourceIsRejectedBeforeTheController() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(CONFIGURED, 0).doFilter(ipnFrom("203.0.113.66"), res, chain);

        assertNull(chain.getRequest(),
                "the chain must NOT have run - a rejected source's body is never parsed or audited");
        assertEquals(403, res.getStatus());
        assertTrue(res.getContentType().startsWith("application/json"));
        // Canonical ApiError envelope, and deliberately uninformative about the allowlist.
        assertEquals("FORBIDDEN", new ObjectMapper().readTree(res.getContentAsString()).get("code").asText());
        assertTrue(!res.getContentAsString().contains(CONFIGURED),
                "the 403 body must not disclose the allowlist to an unlisted caller");
    }

    @Test
    @DisplayName("configured with trustedProxyCount=0: a spoofed X-Forwarded-For cannot get in")
    void spoofedForwardedForIsIgnoredWhenNoProxyIsTrusted() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest req = ipnFrom("203.0.113.66");
        req.addHeader("X-Forwarded-For", "198.51.100.7");   // attacker claims to be 9Pay

        filter(CONFIGURED, 0).doFilter(req, res, chain);

        assertEquals(403, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("configured with trustedProxyCount=1: the ingress-written hop is what is checked")
    void behindOneTrustedProxyTheForwardedHopIsUsed() throws Exception {
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        MockFilterChain allowedChain = new MockFilterChain();
        MockHttpServletRequest ok = ipnFrom("10.42.0.9");           // ingress controller pod
        ok.addHeader("X-Forwarded-For", "198.51.100.7");
        filter(CONFIGURED, 1).doFilter(ok, allowed, allowedChain);
        assertEquals(200, allowed.getStatus());
        assertNotNull(allowedChain.getRequest());

        // Same topology, source outside the range -> denied even though remoteAddr is internal.
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain deniedChain = new MockFilterChain();
        MockHttpServletRequest bad = ipnFrom("10.42.0.9");
        bad.addHeader("X-Forwarded-For", "203.0.113.66");
        filter(CONFIGURED, 1).doFilter(bad, denied, deniedChain);
        assertEquals(403, denied.getStatus());
        assertNull(deniedChain.getRequest());

        // No header at all with a trusted hop configured = undeterminable = denied.
        MockHttpServletResponse missing = new MockHttpServletResponse();
        MockFilterChain missingChain = new MockFilterChain();
        filter(CONFIGURED, 1).doFilter(ipnFrom("10.42.0.9"), missing, missingChain);
        assertEquals(403, missing.getStatus());
        assertNull(missingChain.getRequest());
    }

    // ---------------------------------------------------------------- not configured

    @Test
    @DisplayName("unconfigured: every source is admitted, so a local stack and sim-ninepay keep working")
    void unconfiguredAdmitsEverything() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "172.18.0.5", "203.0.113.66"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter("", 0).doFilter(ipnFrom(ip), res, chain);
            assertNotNull(chain.getRequest(), "must pass through for " + ip);
            assertEquals(200, res.getStatus());
        }
    }

    // ---------------------------------------------------------------- scope + fail-fast

    @Test
    @DisplayName("only /scheme/ipn is filtered - the internal hub endpoints are not 9Pay's edge")
    void onlyTheIpnPathIsFiltered() throws Exception {
        for (String path : new String[] {"/scheme/payout", "/scheme/payout/req-1", "/scheme/balance",
                                         "/scheme/decode-qr", "/actuator/health"}) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.setRemoteAddr("203.0.113.66");                     // would be rejected on /ipn
            filter(CONFIGURED, 0).doFilter(req, res, chain);
            assertNotNull(chain.getRequest(), path + " must not be gated by 9Pay's egress ranges");
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    @DisplayName("a context path does not defeat the path match")
    void contextPathIsStripped() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/np" + NinepayIpnSourceFilter.IPN_PATH);
        req.setContextPath("/np");
        req.setRemoteAddr("203.0.113.66");

        filter(CONFIGURED, 0).doFilter(req, res, chain);

        assertEquals(403, res.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("a malformed range fails the bean, so the service cannot boot 'protected' by a typo")
    void malformedRangeFailsConstruction() {
        assertThrows(IllegalArgumentException.class, () -> filter("198.51.100.0/24, 10.0.0.0/xx", 0));
    }

    // ---------------------------------------------------------------- shipped default

    @Test
    @DisplayName("the SHIPPED application.yml leaves the allowlist EMPTY and the proxy count 0")
    void shippedDefaultsAreEmptyAndZero() throws Exception {
        // Guard against someone "helpfully" baking 9Pay's addresses into the image: they are
        // partner-supplied data that must come from the deployment, and a stale hardcoded
        // range would fail closed against real 9Pay traffic in production.
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        PropertySource<?> src = sources.get(0);
        assertEquals("${GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES:}",
                src.getProperty("gmepay.scheme.ninepay.ipn.allowed-source-ranges"));
        assertEquals("${GMEPAY_SCHEME_NINEPAY_IPN_TRUSTED_PROXY_COUNT:0}",
                src.getProperty("gmepay.scheme.ninepay.ipn.trusted-proxy-count"));
    }
}
