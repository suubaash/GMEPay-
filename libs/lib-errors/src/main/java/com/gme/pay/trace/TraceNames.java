package com.gme.pay.trace;

import java.net.URI;
import java.util.Map;

/**
 * Best-effort identity helpers for trace edges: maps a target host:port to a friendly
 * service name, and decides which inbound paths are noise (health/docs) and should not be
 * reported. Inbound self-identity is always exact (spring.application.name); this map only
 * improves the <em>callee</em> label on outbound calls.
 */
final class TraceNames {

    private TraceNames() {}

    // Canonical host ports from the GMEPay+ inventory. Where a port is shared by several
    // services in different run profiles, the most common money-path owner is chosen; the
    // callee's own inbound filter still reports its exact name regardless.
    private static final Map<Integer, String> PORT_NAMES = Map.ofEntries(
            Map.entry(8084, "payment-executor"),
            Map.entry(8090, "scheme-adapter"),
            Map.entry(8095, "ops-partner-bff"),
            Map.entry(8087, "reporting-compliance"),
            Map.entry(8098, "kyb-adapter"),
            Map.entry(9101, "sim-rate-provider"),
            Map.entry(9102, "sim-scheme"),
            Map.entry(9103, "sim-wallet"),
            Map.entry(9104, "sim-merchant"),
            Map.entry(9105, "sim-gmeremit"),
            Map.entry(18081, "config-registry"),
            Map.entry(18082, "transaction-mgmt"),
            Map.entry(3000, "admin-ui"),
            Map.entry(3001, "partner-portal-ui"));

    static String calleeFor(URI uri) {
        if (uri == null) return "unknown";
        int port = uri.getPort();
        String mapped = PORT_NAMES.get(port);
        if (mapped != null) return mapped;
        String host = uri.getHost();
        if (host == null) return "unknown";
        // Container/DNS style hosts (e.g. "config-registry") are already the service name.
        if (!host.equals("localhost") && !host.equals("127.0.0.1") && !host.matches("[0-9.]+")) {
            return host;
        }
        return host + (port > 0 ? ":" + port : "");
    }

    /** Health, docs, static, and the tracer's own endpoints are not interesting traffic. */
    static boolean skip(String path) {
        if (path == null || path.isEmpty()) return true;
        return path.startsWith("/actuator")
                || path.startsWith("/__data")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger")
                || path.startsWith("/favicon")
                || path.equals("/error")
                || path.startsWith("/webjars")
                || path.equals("/ingest");
    }
}
