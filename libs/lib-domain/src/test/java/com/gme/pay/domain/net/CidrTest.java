package com.gme.pay.domain.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of the {@link Cidr} matcher used by the api-gateway's
 * {@code PartnerIpAllowlistFilter} (Slice 8). Fail-closed semantics throughout: anything
 * malformed must yield {@code false}, never throw, and never trigger a DNS lookup
 * (all fixtures are literals; a resolver fallback would hang the suite offline).
 */
class CidrTest {

    // ----------------------------- IPv4 -------------------------------------

    @Test
    @DisplayName("IPv4 address inside its /24 matches")
    void ipv4InsidePrefix_matches() {
        assertTrue(Cidr.matches("203.0.113.7", "203.0.113.0/24"));
    }

    @Test
    @DisplayName("IPv4 address outside the /24 does not match")
    void ipv4OutsidePrefix_doesNotMatch() {
        assertFalse(Cidr.matches("203.0.114.7", "203.0.113.0/24"));
    }

    @Test
    @DisplayName("/32 matches only the exact host")
    void ipv4HostPrefix_isExact() {
        assertTrue(Cidr.matches("198.51.100.9", "198.51.100.9/32"));
        assertFalse(Cidr.matches("198.51.100.10", "198.51.100.9/32"));
    }

    @Test
    @DisplayName("/0 matches every IPv4 address")
    void ipv4ZeroPrefix_matchesEverything() {
        assertTrue(Cidr.matches("8.8.8.8", "0.0.0.0/0"));
        assertTrue(Cidr.matches("255.255.255.255", "0.0.0.0/0"));
    }

    @Test
    @DisplayName("Non-octet-aligned prefix (/12) masks correctly at the bit level")
    void ipv4BitLevelMask_isHonoured() {
        assertTrue(Cidr.matches("172.16.0.1", "172.16.0.0/12"));
        assertTrue(Cidr.matches("172.31.255.254", "172.16.0.0/12"));
        assertFalse(Cidr.matches("172.32.0.1", "172.16.0.0/12"));
    }

    // ----------------------------- IPv6 -------------------------------------

    @Test
    @DisplayName("IPv6 address inside its /32 matches")
    void ipv6InsidePrefix_matches() {
        assertTrue(Cidr.matches("2001:db8:0:1::5", "2001:db8::/32"));
    }

    @Test
    @DisplayName("IPv6 address outside the /32 does not match")
    void ipv6OutsidePrefix_doesNotMatch() {
        assertFalse(Cidr.matches("2001:db9::1", "2001:db8::/32"));
    }

    @Test
    @DisplayName("'::' elision parses anywhere: leading, trailing, middle")
    void ipv6Elision_parsesEverywhere() {
        assertTrue(Cidr.matches("::1", "::1/128"));
        assertTrue(Cidr.matches("fe80::", "fe80::/10"));
        assertTrue(Cidr.matches("2001:db8::42:1", "2001:db8::42:0/112"));
    }

    @Test
    @DisplayName("IPv4 address never matches an IPv6 CIDR and vice versa")
    void familyMismatch_neverMatches() {
        assertFalse(Cidr.matches("203.0.113.7", "2001:db8::/32"));
        assertFalse(Cidr.matches("2001:db8::1", "203.0.113.0/24"));
    }

    // --------------------------- malformed input ----------------------------

    @Test
    @DisplayName("Malformed address or CIDR fails closed (false, no throw)")
    void malformedInput_failsClosed() {
        assertFalse(Cidr.matches(null, "203.0.113.0/24"));
        assertFalse(Cidr.matches("203.0.113.7", null));
        assertFalse(Cidr.matches("not-an-ip", "203.0.113.0/24"));
        assertFalse(Cidr.matches("203.0.113.7", "203.0.113.0"));      // no prefix
        assertFalse(Cidr.matches("203.0.113.7", "203.0.113.0/"));     // empty prefix
        assertFalse(Cidr.matches("203.0.113.7", "203.0.113.0/33"));   // v4 prefix > 32
        assertFalse(Cidr.matches("2001:db8::1", "2001:db8::/129"));   // v6 prefix > 128
        assertFalse(Cidr.matches("203.0.113.7", "203.0.113.0/abc"));  // non-numeric prefix
        assertFalse(Cidr.matches("1.2.3.4.5", "0.0.0.0/0"));          // five octets
        assertFalse(Cidr.matches("1.2.3.256", "0.0.0.0/0"));          // octet > 255
        assertFalse(Cidr.matches("2001:db8:::1", "::/0"));            // double elision
        assertFalse(Cidr.matches("::ffff:1.2.3.4", "::/0"));          // mixed v6/v4 notation
    }

    @Test
    @DisplayName("isValidAddress accepts literals, rejects garbage and CIDRs")
    void isValidAddress_acceptsLiteralsOnly() {
        assertTrue(Cidr.isValidAddress("203.0.113.7"));
        assertTrue(Cidr.isValidAddress("2001:db8::1"));
        assertTrue(Cidr.isValidAddress("::1"));
        assertFalse(Cidr.isValidAddress(null));
        assertFalse(Cidr.isValidAddress(""));
        assertFalse(Cidr.isValidAddress("evil.example.com")); // hostname: never resolved
        assertFalse(Cidr.isValidAddress("203.0.113.0/24"));   // CIDR is not an address
        assertFalse(Cidr.isValidAddress("1:2:3:4:5:6:7"));    // 7 groups, no elision
    }

    // ----------------------------- isInternal -------------------------------

    @Test
    @DisplayName("RFC1918 / loopback / link-local / ULA hops are internal")
    void internalRanges_areInternal() {
        assertTrue(Cidr.isInternal("10.1.2.3"));
        assertTrue(Cidr.isInternal("127.0.0.1"));
        assertTrue(Cidr.isInternal("172.16.5.5"));
        assertTrue(Cidr.isInternal("192.168.1.9"));
        assertTrue(Cidr.isInternal("169.254.0.7"));
        assertTrue(Cidr.isInternal("::1"));
        assertTrue(Cidr.isInternal("fc00::1"));
        assertTrue(Cidr.isInternal("fdab::9"));
        assertTrue(Cidr.isInternal("fe80::1"));
    }

    @Test
    @DisplayName("Public addresses are not internal; unparseable input is not internal")
    void publicAndGarbage_areNotInternal() {
        assertFalse(Cidr.isInternal("203.0.113.7"));
        assertFalse(Cidr.isInternal("8.8.8.8"));
        assertFalse(Cidr.isInternal("172.32.0.1"));   // just past 172.16/12
        assertFalse(Cidr.isInternal("2001:db8::1"));
        assertFalse(Cidr.isInternal("garbage"));
        assertFalse(Cidr.isInternal(null));
    }
}
