package com.gme.pay.scheme.ninepay.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Source-IP allowlist for the inbound 9Pay IPN edge — gap <b>T5-7</b>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Gap <b>T5-6</b> is unfixable in code we own: 9Pay leaves the {@code code} field
 * <em>outside</em> the signed string, and a bank reversal ({@code code=009}) reuses the
 * original transfer's {@code created_at}. A captured success IPN with {@code code} rewritten
 * to {@code 009} therefore still verifies cryptographically, and
 * {@code NinepayIpnReplayGuard} cannot tell it from a genuine reversal. Until 9Pay signs
 * {@code code} (external ask), <b>the only thing that stops that forgery is proving the
 * request came from 9Pay's network</b>. That is this class.
 *
 * <h2>Two explicit states — never a silent one</h2>
 *
 * <ol>
 *   <li><b>Not configured</b> (no ranges): {@link #isConfigured()} is {@code false} and the
 *       filter admits every source, so a local/dev stack and the sim keep working. This state
 *       is <em>loudly logged at WARN on every boot</em> — an unconfigured allowlist means the
 *       T5-6 compensating control is INACTIVE, and that fact must be visible in the log of
 *       any environment that forgot it, not inferred from a missing env var.</li>
 *   <li><b>Configured</b> (≥1 range): <b>fail closed</b>. Anything outside the ranges is
 *       rejected 403 and logged; an unresolvable client address is also rejected.</li>
 * </ol>
 *
 * <p>A <b>malformed</b> range is neither state: it throws from the constructor, so the
 * context fails to start. Booting with a typo'd CIDR would give an operator who believes the
 * edge is protected an edge that is not.</p>
 *
 * <h2>What this is NOT</h2>
 *
 * <p>This is the <em>service-level</em> layer, for the case where the adapter pod/container is
 * reachable directly. The primary control is the ingress
 * ({@code deploy/helm/gmepay/templates/ingress-ipn.yaml}), because an in-process check still
 * accepts and parses the request body before deciding. Both layers read the same operator-
 * supplied ranges. <b>This repo does not ship 9Pay's ranges as a default</b> — they are
 * partner-supplied data (see {@code Documentation/RUNBOOK_BACKUP_DR.md} sibling note in
 * {@code outputs/agent/fix_t3-rpo-and-ipn-allowlist_2026-07-28.md} §B).</p>
 */
public final class IpnSourceAllowlist {

    /** A single CIDR block, or a bare address (treated as /32 or /128). */
    private record Cidr(byte[] network, int prefixBits, String text) {

        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;               // never compare an IPv4 address to an IPv6 block
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainder)) & 0xFF;
            return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> ranges;

    private IpnSourceAllowlist(List<Cidr> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /**
     * Parses a comma- (or whitespace-) separated list of CIDR blocks / bare addresses.
     * A blank or null value yields the NOT-CONFIGURED instance. Comma separation is used
     * rather than a YAML list so one env var (
     * {@code GMEPAY_SCHEME_NINEPAY_IPN_ALLOWED_SOURCE_RANGES}) can carry it in every
     * deployment shape.
     *
     * @throws IllegalArgumentException on any entry that is not a literal IPv4/IPv6 address
     *         or CIDR block, or whose prefix length is out of range for its family
     */
    public static IpnSourceAllowlist parse(String raw) {
        List<Cidr> parsed = new ArrayList<>();
        if (raw != null) {
            for (String token : raw.split("[,\\s]+")) {
                String entry = token.trim();
                if (entry.isEmpty()) {
                    continue;
                }
                parsed.add(parseOne(entry));
            }
        }
        return new IpnSourceAllowlist(parsed);
    }

    private static Cidr parseOne(String entry) {
        String addrPart = entry;
        int prefix = -1;
        int slash = entry.lastIndexOf('/');
        if (slash >= 0) {
            addrPart = entry.substring(0, slash);
            String prefixPart = entry.substring(slash + 1);
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "9Pay IPN allowlist: '" + entry + "' has a non-numeric prefix length", e);
            }
        }
        byte[] network = literalAddress(addrPart);
        if (network == null) {
            throw new IllegalArgumentException("9Pay IPN allowlist: '" + entry
                    + "' is not a literal IPv4/IPv6 address or CIDR block "
                    + "(hostnames are rejected on purpose — a DNS lookup is not an ACL)");
        }
        int maxBits = network.length * 8;
        if (prefix < 0) {
            prefix = maxBits;               // bare address == single host
        }
        if (prefix > maxBits) {
            throw new IllegalArgumentException("9Pay IPN allowlist: '" + entry + "' prefix /"
                    + prefix + " exceeds /" + maxBits + " for this address family");
        }
        return new Cidr(network, prefix, entry);
    }

    /**
     * Parses a literal address WITHOUT any name resolution, returning null when the text is
     * not one. {@code InetAddress.getByName} would happily do a DNS lookup for anything that
     * is not IPv6-shaped, so IPv4 is parsed by hand here: an ACL that silently resolves names
     * is an ACL whose meaning changes when someone else's DNS changes, and a lookup in the
     * request path would also be a latency/DoS surface.
     */
    private static byte[] literalAddress(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String candidate = text;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.indexOf(':') >= 0) {
            // Contains a colon => InetAddress treats it as an IPv6 literal and never resolves.
            // This also covers the IPv4-mapped form ::ffff:a.b.c.d, which yields 4 bytes.
            int zone = candidate.indexOf('%');
            if (zone >= 0) {
                candidate = candidate.substring(0, zone);   // drop a link-local scope id
            }
            try {
                return InetAddress.getByName(candidate).getAddress();
            } catch (UnknownHostException | SecurityException e) {
                return null;
            }
        }
        return parseIpv4(candidate);
    }

    /** Strict dotted-quad parse — no DNS, no shorthand ("10.1" is not an address here). */
    private static byte[] parseIpv4(String text) {
        byte[] out = new byte[4];
        int octet = 0;
        int value = -1;
        int digits = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i == text.length() ? '.' : text.charAt(i);
            if (c == '.') {
                if (digits == 0 || octet > 3) {
                    return null;
                }
                out[octet++] = (byte) value;
                value = -1;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                if (++digits > 3) {
                    return null;
                }
                value = (value < 0 ? 0 : value) * 10 + (c - '0');
                if (value > 255) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return octet == 4 ? out : null;
    }

    /** True when at least one range was configured, i.e. the allowlist is ENFORCING. */
    public boolean isConfigured() {
        return !ranges.isEmpty();
    }

    public int size() {
        return ranges.size();
    }

    /** The configured ranges verbatim, for the startup log. */
    public List<String> describe() {
        if (ranges.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(ranges.size());
        for (Cidr c : ranges) {
            out.add(c.text());
        }
        return List.copyOf(out);
    }

    /**
     * Whether {@code ip} falls inside any configured range.
     *
     * <p><b>Fail closed:</b> an unparseable / null address returns {@code false} when the
     * allowlist is configured. When it is NOT configured this returns {@code true} for
     * everything — the caller is responsible for having logged that state (see the class
     * javadoc); this method never silently pretends to be enforcing.</p>
     */
    public boolean permits(String ip) {
        if (ranges.isEmpty()) {
            return true;
        }
        byte[] addr = literalAddress(ip == null ? null : stripPort(ip.trim()));
        if (addr == null) {
            return false;
        }
        for (Cidr c : ranges) {
            if (c.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the address to test against the allowlist.
     *
     * <p>{@code trustedProxyCount} is the number of hops between 9Pay and this process that
     * rewrite/append {@code X-Forwarded-For}. It defaults to <b>0</b>, meaning
     * {@code remoteAddr} is used and the header is IGNORED — the safe default, because
     * {@code X-Forwarded-For} is attacker-controlled unless a trusted hop overwrote it.
     * Behind one ingress controller set it to 1.</p>
     *
     * <p>With N trusted hops the client is the entry N-from-the-right of the header
     * (nginx/ALB append the peer they received from), so the index is
     * {@code size - N}. A header too short for N hops yields {@code null} = "cannot
     * determine", which {@link #permits(String)} rejects when enforcing.</p>
     */
    public static String resolveClientIp(String remoteAddr, String forwardedFor, int trustedProxyCount) {
        if (trustedProxyCount <= 0) {
            return remoteAddr == null ? null : stripPort(remoteAddr.trim());
        }
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        String[] hops = forwardedFor.split(",");
        List<String> cleaned = new ArrayList<>(hops.length);
        for (String hop : hops) {
            String h = hop.trim();
            if (!h.isEmpty()) {
                cleaned.add(stripPort(h));
            }
        }
        int index = cleaned.size() - trustedProxyCount;
        if (index < 0 || index >= cleaned.size()) {
            return null;
        }
        return cleaned.get(index);
    }

    /** Strips {@code :port} from an IPv4 literal and the brackets from {@code [v6]:port}. */
    private static String stripPort(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        int colon = value.indexOf(':');
        if (colon > 0 && value.indexOf(':', colon + 1) < 0) {
            return value.substring(0, colon);       // exactly one colon => IPv4:port
        }
        return value;
    }
}
