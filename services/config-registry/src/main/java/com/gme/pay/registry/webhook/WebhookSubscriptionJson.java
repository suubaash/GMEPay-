package com.gme.pay.registry.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Canonical JSON writer for partner-webhook-subscription rows — the byte
 * representation that goes into the ADR-007 audit hash chain as the BEFORE /
 * AFTER snapshot of a step-8 webhook save or an activation provisioning.
 *
 * <p>Same rationale as {@code PrefundingJson} / {@code ContactJson}: the
 * bytes that feed the hash chain must be identical on every machine running
 * the same write path, so the canonicalisation is hand-rolled with a fixed
 * key order and no dependence on live Jackson configuration.
 *
 * <p>SECURITY: {@code signing_secret_hash} is intentionally EXCLUDED — secret
 * material (even one-way digests) does not belong in the audit chain. The
 * provisioning fact is captured by {@code endpointId} + {@code status} +
 * {@code lastRotatedAt}.
 *
 * <p>Shape: one object with the fixed key sequence {@code id, environment,
 * url, eventTypes, endpointId, status, lastRotatedAt}. Creation/update stamps
 * are excluded — they describe the row's storage history, not the
 * subscription fact, and the audit row carries its own {@code recorded_at}.
 */
final class WebhookSubscriptionJson {

    private WebhookSubscriptionJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for one partner-webhook-subscription row. */
    static byte[] canonical(PartnerWebhookSubscriptionEntity e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"environment\":").append(jsonString(e.getEnvironment())).append(',');
        sb.append("\"url\":").append(jsonString(e.getUrl())).append(',');
        sb.append("\"eventTypes\":").append(jsonString(e.getEventTypesCsv())).append(',');
        sb.append("\"endpointId\":").append(jsonString(e.getEndpointId())).append(',');
        sb.append("\"status\":").append(jsonString(
                e.getStatus() == null ? null : e.getStatus().name())).append(',');
        sb.append("\"lastRotatedAt\":").append(instant(e.getLastRotatedAt()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** MICROS-truncated instants render via Instant.toString (deterministic ISO-8601). */
    private static String instant(Instant value) {
        return value == null ? "null" : '"' + value.toString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the one in
     * {@code PrefundingJson} / {@code ContactJson} for the same input (kept
     * local so this package does not reach into other packages' private
     * helpers).
     */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
