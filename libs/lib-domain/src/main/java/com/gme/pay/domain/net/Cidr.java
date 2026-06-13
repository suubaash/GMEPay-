package com.gme.pay.domain.net;

import java.util.List;

/**
 * Dependency-free CIDR matcher for IPv4 and IPv6 — Slice 8 (api-gateway
 * {@code PartnerIpAllowlistFilter}).
 *
 * <p>Why hand-rolled: no CIDR library is on the platform classpath and adding one for a
 * ~100-line bit-mask comparison is not worth a new external dependency. Why here: lib-domain
 * is the shared, framework-free home for cross-service value logic (same altitude as
 * {@code Partner} / {@code Rule}); the gateway and any future consumer (e.g. webhook egress
 * pinning) share one implementation.
 *
 * <p>Parsing is strictly literal — deliberately NOT {@code InetAddress.getByName}, which
 * silently falls back to a DNS resolver lookup for anything that does not parse as an IP
 * literal (the same trap config-registry's {@code PartnerIpAllowlistService} documents).
 * Supported shapes mirror the V026 service-side validation:
 *
 * <ul>
 *   <li>IPv4 dotted quad, four octets 0..255 — {@code 203.0.113.7}</li>
 *   <li>IPv6 hex groups with at most one {@code ::} elision — {@code 2001:db8::1}.
 *       Mixed IPv6/IPv4 notation ({@code ::ffff:1.2.3.4}) is NOT supported; callers
 *       canonicalise upstream, exactly as the allowlist write path requires.</li>
 * </ul>
 *
 * <p>All methods are null-safe and never throw on malformed input: a value that does not
 * parse simply does not match (fail-closed for {@link #matches}; "not selectable" for the
 * X-Forwarded-For hop scan in the gateway).
 */
public final class Cidr {

    /**
     * Ranges considered "internal" infrastructure hops when scanning X-Forwarded-For:
     * RFC1918 private IPv4, loopback, link-local, the unspecified address, IPv6 ULA
     * (fc00::/7) and IPv6 link-local (fe80::/10). A hop inside any of these is a proxy /
     * LB on our side of the edge, not the originating client.
     */
    private static final List<String> INTERNAL_RANGES = List.of(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "0.0.0.0/32",
            "::1/128",
            "::/128",
            "fc00::/7",
            "fe80::/10");

    private Cidr() {
        // utility
    }

    /** True when {@code address} is a parseable IPv4/IPv6 literal (no DNS, no CIDR prefix). */
    public static boolean isValidAddress(String address) {
        return parse(address) != null;
    }

    /**
     * True when {@code address} falls inside one of the well-known internal/private ranges
     * ({@link #INTERNAL_RANGES}). Unparseable input is NOT internal (it is also not
     * {@linkplain #isValidAddress valid}, so callers filtering hops check validity first).
     */
    public static boolean isInternal(String address) {
        for (String range : INTERNAL_RANGES) {
            if (matches(address, range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the IP literal {@code address} falls inside {@code cidr}
     * (e.g. {@code matches("203.0.113.7", "203.0.113.0/24")}).
     *
     * <p>Fail-closed: malformed address, malformed CIDR, out-of-range prefix, or an
     * address-family mismatch (IPv4 vs IPv6) all return {@code false}.
     */
    public static boolean matches(String address, String cidr) {
        if (address == null || cidr == null) {
            return false;
        }
        String trimmedCidr = cidr.trim();
        int slash = trimmedCidr.indexOf('/');
        if (slash <= 0 || slash != trimmedCidr.lastIndexOf('/')
                || slash == trimmedCidr.length() - 1) {
            return false;
        }
        byte[] addr = parse(address);
        byte[] network = parse(trimmedCidr.substring(0, slash));
        if (addr == null || network == null || addr.length != network.length) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(trimmedCidr.substring(slash + 1));
        } catch (NumberFormatException notANumber) {
            return false;
        }
        if (prefix < 0 || prefix > addr.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != network[i]) {
                return false;
            }
        }
        int remainderBits = prefix % 8;
        if (remainderBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainderBits)) & 0xFF;
        return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    // ------------------------- literal parsing ------------------------------

    /**
     * Parse an IP literal to network-order bytes (4 for IPv4, 16 for IPv6) or {@code null}
     * when malformed. Never resolves hostnames.
     */
    static byte[] parse(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.indexOf(':') >= 0 ? parseIpv6(trimmed) : parseIpv4(trimmed);
    }

    private static byte[] parseIpv4(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3 || !isDigits(octet)) {
                return null;
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] parseIpv6(String address) {
        if (address.contains(".")) {
            return null; // mixed v6/v4 notation: canonicalise upstream (matches V026 rules)
        }
        int elision = address.indexOf("::");
        if (elision != address.lastIndexOf("::")) {
            return null; // at most one '::'
        }
        String head;
        String tail;
        if (elision >= 0) {
            head = address.substring(0, elision);
            tail = address.substring(elision + 2);
        } else {
            head = address;
            tail = null;
        }
        int[] headGroups = parseGroups(head);
        int[] tailGroups = tail == null ? new int[0] : parseGroups(tail);
        if (headGroups == null || tailGroups == null) {
            return null;
        }
        int explicit = headGroups.length + tailGroups.length;
        if (elision < 0 && explicit != 8) {
            return null; // no elision => exactly 8 groups
        }
        if (elision >= 0 && explicit > 7) {
            return null; // elision must stand for at least one zero group
        }
        byte[] bytes = new byte[16];
        for (int i = 0; i < headGroups.length; i++) {
            bytes[i * 2] = (byte) (headGroups[i] >> 8);
            bytes[i * 2 + 1] = (byte) headGroups[i];
        }
        for (int i = 0; i < tailGroups.length; i++) {
            int at = 16 - (tailGroups.length - i) * 2;
            bytes[at] = (byte) (tailGroups[i] >> 8);
            bytes[at + 1] = (byte) tailGroups[i];
        }
        return bytes;
    }

    /** Hex groups of one elision-free half; {@code null} on any malformed group. */
    private static int[] parseGroups(String half) {
        if (half.isEmpty()) {
            return new int[0];
        }
        String[] raw = half.split(":", -1);
        int[] groups = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            String group = raw[i];
            if (group.isEmpty() || group.length() > 4 || !isHex(group)) {
                return null;
            }
            groups[i] = Integer.parseInt(group, 16);
        }
        return groups;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
