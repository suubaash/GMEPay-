package com.gme.pay.e2e.load;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The safety interlock in front of the load harness (gap register <b>T3-5</b>).
 *
 * <p>A load generator is the one tool in this repo whose <em>success</em> is indistinguishable from
 * an attack: it exists to push a money path until something saturates. Pointed at the wrong host it
 * would create real authorizations, drain real prefunded float, and page whoever is on call. So the
 * harness refuses to emit a single request until BOTH of these hold:
 *
 * <ol>
 *   <li><b>Every</b> target URL resolves to an unambiguously local / dev host — see
 *       {@link #LOCAL_HOSTS} and {@link #LOCAL_SUFFIXES}. One non-local target aborts the whole run;
 *       there is no "mostly local" mode, because a partial run still lands real traffic somewhere.</li>
 *   <li>The operator passed {@value #ACK_FLAG} explicitly. A hostname check alone is not enough — a
 *       tunnel, an SSH forward, or a hosts-file entry can make a production ingress answer on
 *       {@code localhost}. The flag is the human saying "I know what this box is wired to".</li>
 * </ol>
 *
 * <p>It also rejects a few shapes that are local-looking but wrong: a non-{@code http}/{@code https}
 * scheme, a URL with credentials in it, and any host or path that carries a production marker
 * ({@link #PROD_MARKERS}) — because {@code prod.localhost} and an SSH-forwarded
 * {@code localhost:8080/prod/...} are exactly how this goes wrong in practice.
 *
 * <p><b>This class is deliberately pure</b> (no I/O, no clock, no statics that mutate) so
 * {@code LoadTargetGuardTest} can prove the refusal in the ordinary {@code test} task — the guard is
 * verified on every {@code gradlew build}, without ever starting a fleet.
 */
public final class LoadTargetGuard {

    /** The explicit acknowledgement the operator must pass. Nothing else unlocks the harness. */
    public static final String ACK_FLAG = "--i-know-this-is-not-prod";

    /** Exact hostnames accepted as local. */
    public static final Set<String> LOCAL_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "[::1]",
            // The WSL2/Docker-Desktop loopback aliases the compose stack is reachable on from Windows
            // (memory: docker runs in the WSL distro; host ports are published to the Windows side).
            "host.docker.internal",
            "kubernetes.docker.internal",
            "gateway.docker.internal");

    /** Hostname suffixes accepted as local/dev (e.g. {@code payment-executor.localhost}). */
    public static final List<String> LOCAL_SUFFIXES = List.of(".localhost", ".local", ".internal.test");

    /**
     * Substrings that veto a target even when the host looks local. A forwarded port or a hosts-file
     * alias can put a production ingress on {@code localhost}; if the operator typed anything that
     * says "production", we take them at their word and refuse.
     */
    public static final List<String> PROD_MARKERS =
            List.of("prod", "prd", "live", "gmepay.com", "gmeremit.com");

    private LoadTargetGuard() {
    }

    /** Thrown when the harness must not run. Carries the full reason list, not just the first. */
    public static final class RefusedException extends RuntimeException {
        private final List<String> reasons;

        RefusedException(List<String> reasons) {
            super("load harness REFUSED to run:\n  - " + String.join("\n  - ", reasons));
            this.reasons = List.copyOf(reasons);
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    /**
     * Verifies the run is allowed. Returns normally only when every target is local AND the
     * acknowledgement flag was given.
     *
     * @param targets     every base URL the run will touch (money-path services AND scrape targets)
     * @param acknowledged whether {@value #ACK_FLAG} was present on the command line
     * @throws RefusedException with every reason found, so one run surfaces all problems
     */
    public static void requireLocalDevTarget(List<String> targets, boolean acknowledged) {
        List<String> reasons = new ArrayList<>();

        if (targets == null || targets.isEmpty()) {
            reasons.add("no target URL was supplied - there is nothing to verify, so nothing may run");
        } else {
            // LinkedHashSet: de-duplicate (services share hosts) but keep the operator's order in the
            // message, so the offending URL is easy to spot.
            for (String target : new LinkedHashSet<>(targets)) {
                reasons.addAll(inspect(target));
            }
        }

        if (!acknowledged) {
            reasons.add("missing " + ACK_FLAG + " - a local-looking host is not proof (a tunnel or a "
                    + "hosts entry can put production on localhost); pass the flag to confirm you know "
                    + "what this machine is wired to");
        }

        if (!reasons.isEmpty()) {
            throw new RefusedException(reasons);
        }
    }

    /** All the reasons a single URL is unacceptable (empty list = acceptable). */
    private static List<String> inspect(String target) {
        List<String> reasons = new ArrayList<>();
        if (target == null || target.isBlank()) {
            reasons.add("blank target URL");
            return reasons;
        }

        URI uri;
        try {
            uri = URI.create(target.trim());
        } catch (IllegalArgumentException e) {
            reasons.add(target + " - not a parsable URL (" + e.getMessage() + ")");
            return reasons;
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            reasons.add(target + " - scheme must be http or https (got '"
                    + (uri.getScheme() == null ? "none" : uri.getScheme()) + "'); a bare host:port is "
                    + "ambiguous and is not accepted");
            return reasons;
        }
        if (uri.getUserInfo() != null) {
            reasons.add(target + " - URL carries credentials; the harness never sends embedded "
                    + "user:password (use --internal-secret)");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            // URI.getHost() returns null for a raw IPv6 literal without brackets, among others.
            reasons.add(target + " - no host could be parsed out of the URL");
            return reasons;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);

        boolean local = LOCAL_HOSTS.contains(lowerHost)
                || LOCAL_SUFFIXES.stream().anyMatch(lowerHost::endsWith);
        if (!local) {
            reasons.add(target + " - host '" + host + "' is not local/dev. Accepted: "
                    + LOCAL_HOSTS.stream().sorted().toList() + " or a host ending in " + LOCAL_SUFFIXES
                    + ". This harness drives a REAL money path; it will not point at anything else.");
        }

        // Production markers anywhere in host or path, even on an otherwise-local host.
        String haystack = (lowerHost + " " + (uri.getPath() == null ? "" : uri.getPath()))
                .toLowerCase(Locale.ROOT);
        for (String marker : PROD_MARKERS) {
            if (haystack.contains(marker)) {
                reasons.add(target + " - contains the production marker '" + marker + "'"
                        + (local ? " on an otherwise-local host; a tunnel or hosts entry is exactly how "
                        + "this goes wrong, so it is refused anyway" : ""));
            }
        }
        return reasons;
    }
}
