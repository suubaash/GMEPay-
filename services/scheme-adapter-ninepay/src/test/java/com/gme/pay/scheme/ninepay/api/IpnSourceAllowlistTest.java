package com.gme.pay.scheme.ninepay.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap <b>T5-7</b> — the source-IP allowlist matcher for the 9Pay IPN edge.
 *
 * <p>No 9Pay address appears anywhere in these tests: the ranges are partner-supplied data
 * this repo deliberately does not carry, so the fixtures use RFC 5737/3849 documentation
 * ranges. What is asserted is the DECISION LOGIC — unconfigured admits (and says so),
 * configured fails closed, malformed refuses to load.</p>
 */
class IpnSourceAllowlistTest {

    // ---------------------------------------------------------------- two explicit states

    @Test
    @DisplayName("empty config = NOT configured, and admits everything (local/dev must keep working)")
    void emptyAllowlistIsNotConfiguredAndAdmitsEverything() {
        for (String raw : new String[] {null, "", "   ", ",", " , , "}) {
            IpnSourceAllowlist a = IpnSourceAllowlist.parse(raw);
            assertFalse(a.isConfigured(), "raw=" + raw);
            assertEquals(0, a.size());
            assertTrue(a.describe().isEmpty());
            assertTrue(a.permits("203.0.113.7"));
            assertTrue(a.permits("127.0.0.1"));
            // Even garbage is admitted when NOT configured: the state is "no opinion", and
            // NinepayIpnSourceFilter is what shouts about it. What must never happen is this
            // state quietly *looking* like enforcement.
            assertTrue(a.permits("not-an-ip"));
        }
    }

    @Test
    @DisplayName("configured = fail closed: outside the ranges is denied, and so is an unresolvable address")
    void configuredAllowlistFailsClosed() {
        IpnSourceAllowlist a = IpnSourceAllowlist.parse("198.51.100.0/24, 203.0.113.9");

        assertTrue(a.isConfigured());
        assertEquals(2, a.size());
        assertEquals(List.of("198.51.100.0/24", "203.0.113.9"), a.describe());

        assertTrue(a.permits("198.51.100.1"));
        assertTrue(a.permits("198.51.100.255"));
        assertTrue(a.permits("203.0.113.9"));

        assertFalse(a.permits("198.51.101.1"));
        assertFalse(a.permits("203.0.113.10"));
        assertFalse(a.permits("127.0.0.1"), "loopback is NOT special-cased once enforcing");
        assertFalse(a.permits(null));
        assertFalse(a.permits(""));
        assertFalse(a.permits("not-an-ip"));
    }

    // ---------------------------------------------------------------- prefix arithmetic

    @Test
    @DisplayName("non-byte-aligned prefixes are masked correctly")
    void nonByteAlignedPrefixes() {
        IpnSourceAllowlist a = IpnSourceAllowlist.parse("203.0.113.128/25");
        assertTrue(a.permits("203.0.113.128"));
        assertTrue(a.permits("203.0.113.255"));
        assertFalse(a.permits("203.0.113.127"));
        assertFalse(a.permits("203.0.113.0"));

        assertTrue(IpnSourceAllowlist.parse("10.0.0.0/0").permits("203.0.113.1"));
        assertTrue(IpnSourceAllowlist.parse("192.0.2.5/32").permits("192.0.2.5"));
        assertFalse(IpnSourceAllowlist.parse("192.0.2.5/32").permits("192.0.2.6"));
    }

    @Test
    @DisplayName("a bare address is a single host, not a whole subnet")
    void bareAddressIsSingleHost() {
        IpnSourceAllowlist a = IpnSourceAllowlist.parse("192.0.2.5");
        assertTrue(a.permits("192.0.2.5"));
        assertFalse(a.permits("192.0.2.4"));
    }

    @Test
    @DisplayName("IPv6 blocks work and never cross families with IPv4")
    void ipv6AndFamilySeparation() {
        IpnSourceAllowlist v6 = IpnSourceAllowlist.parse("2001:db8::/32");
        assertTrue(v6.permits("2001:db8::1"));
        assertTrue(v6.permits("2001:db8:ffff::9"));
        assertFalse(v6.permits("2001:db9::1"));
        assertFalse(v6.permits("203.0.113.1"));

        // An IPv4 /24 must not match an IPv6 address that shares leading bytes.
        assertFalse(IpnSourceAllowlist.parse("198.51.100.0/24").permits("2001:db8::1"));
    }

    @Test
    @DisplayName("an IPv4-mapped IPv6 peer address (::ffff:a.b.c.d) matches the IPv4 range")
    void ipv4MappedAddressMatchesIpv4Range() {
        // Some container/proxy stacks report the peer this way. Treating it as unmatched
        // would deny a legitimate source, so this normalisation is load-bearing, not cosmetic.
        assertTrue(IpnSourceAllowlist.parse("198.51.100.0/24").permits("::ffff:198.51.100.7"));
    }

    @Test
    @DisplayName("host:port and [v6]:port forms are accepted")
    void portsAreStripped() {
        assertTrue(IpnSourceAllowlist.parse("198.51.100.0/24").permits("198.51.100.7:54321"));
        assertTrue(IpnSourceAllowlist.parse("2001:db8::/32").permits("[2001:db8::1]:443"));
    }

    // ---------------------------------------------------------------- malformed = no boot

    @Test
    @DisplayName("a malformed range refuses to load rather than becoming a third, silent state")
    void malformedRangesThrow() {
        for (String bad : new String[] {
                "198.51.100.0/xx",              // non-numeric prefix
                "198.51.100.0/33",              // prefix beyond the family
                "2001:db8::/129",
                "ninepay.example.com",          // a hostname is not an ACL
                "ninepay.example.com/32",
                "999.1.1.1",                    // not a valid literal
                "10.1",                         // no dotted-quad shorthand
                "198.51.100.0/24 , oops"}) {
            assertThrows(IllegalArgumentException.class, () -> IpnSourceAllowlist.parse(bad),
                    "raw=" + bad);
        }
    }

    // ---------------------------------------------------------------- client-IP resolution

    @Test
    @DisplayName("trustedProxyCount=0 uses the socket peer and IGNORES X-Forwarded-For")
    void zeroTrustedProxiesIgnoresForwardedFor() {
        // The spoofing case: an attacker sets XFF to a 9Pay address. With no trusted hop in
        // front of us the header must not be consulted at all.
        assertEquals("203.0.113.50",
                IpnSourceAllowlist.resolveClientIp("203.0.113.50", "198.51.100.7", 0));
        assertEquals("203.0.113.50",
                IpnSourceAllowlist.resolveClientIp("203.0.113.50:5555", null, 0));
        assertNull(IpnSourceAllowlist.resolveClientIp(null, "198.51.100.7", 0));
    }

    @Test
    @DisplayName("with N trusted hops the client is the entry N-from-the-right of X-Forwarded-For")
    void trustedProxiesTakeTheRightHop() {
        // ingress-nginx: XFF = "<client>", remoteAddr = controller pod => 1 trusted hop.
        assertEquals("198.51.100.7",
                IpnSourceAllowlist.resolveClientIp("10.42.0.9", "198.51.100.7", 1));
        // LB -> nginx -> pod: XFF = "<client>, <lb>" => 2 trusted hops.
        assertEquals("198.51.100.7",
                IpnSourceAllowlist.resolveClientIp("10.42.0.9", "198.51.100.7, 10.0.0.5", 2));
        // A client-supplied prefix cannot promote itself: with 1 trusted hop only the last
        // entry counts, and that is the one the trusted proxy wrote.
        assertEquals("198.51.100.7",
                IpnSourceAllowlist.resolveClientIp("10.42.0.9", "1.2.3.4, 198.51.100.7", 1));
    }

    @Test
    @DisplayName("a header too short for the configured hop count is 'undeterminable', i.e. denied")
    void tooShortForwardedForIsUndeterminable() {
        assertNull(IpnSourceAllowlist.resolveClientIp("10.42.0.9", "198.51.100.7", 2));
        assertNull(IpnSourceAllowlist.resolveClientIp("10.42.0.9", null, 1));
        assertNull(IpnSourceAllowlist.resolveClientIp("10.42.0.9", "  ", 1));
        assertFalse(IpnSourceAllowlist.parse("198.51.100.0/24").permits(null));
    }
}
