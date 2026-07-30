package com.gme.pay.e2e.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Turns a non-2xx HTTP response into a structured code, and decides whether it is a
 * <b>decline</b> (the platform correctly refusing money) or an <b>error</b> (the platform failing).
 *
 * <p>This distinction only became possible recently. The money path now returns canonical codes from
 * {@code libs/lib-errors} {@code ErrorCode} — {@code TRANSACTION_LIMIT_EXCEEDED} (422),
 * {@code SCHEME_CLOSED} (409, added by T3-6), {@code SCHEME_OPERATION_UNSUPPORTED} (422) — in the
 * {@code ApiError} envelope {@code {code, message, retryable, requestId}}, and the wallet endpoint
 * returns {@code {status: DECLINED, declineReason: MERCHANT_INACTIVE}}. Without those a load report
 * could only say "18% non-2xx", which hides whether the platform buckled or behaved.
 *
 * <p>The classification rule is deliberately conservative:
 * <ul>
 *   <li>4xx <b>with</b> a recognisable structured code → {@code DECLINED}. Business outcome.</li>
 *   <li>4xx <b>without</b> one → {@code ERROR}. An unstructured 4xx under load is usually the harness
 *       or the contract being wrong, and calling it a decline would make a broken run look healthy.</li>
 *   <li>429 → {@code ERROR}, even though it is structured: being rate-limited means the platform
 *       could not take the offered load, which is exactly the finding a capacity run is looking for.
 *       (Named as {@code RATE_LIMITED} so it is never mistaken for a scheme decline.)</li>
 *   <li>5xx and every transport failure → {@code ERROR}.</li>
 * </ul>
 */
final class ResponseCodes {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * HTTP statuses whose structured 4xx code is a genuine business decline.
     * 409 is included because {@code SCHEME_CLOSED} (T3-6 operating hours) lands there.
     */
    private static final Set<Integer> DECLINE_STATUSES = Set.of(400, 402, 403, 404, 409, 422);

    private ResponseCodes() {
    }

    /** Builds the {@link Outcome} for a completed HTTP exchange. */
    static Outcome classify(int status, String body, long latencyNs) {
        if (status >= 200 && status < 300) {
            return Outcome.ok(latencyNs, new long[0], new String[0]);
        }
        if (status == 429) {
            return Outcome.error("RATE_LIMITED", status, latencyNs);
        }
        String code = extractCode(body);
        if (DECLINE_STATUSES.contains(status) && code != null) {
            return Outcome.declined(code, status, latencyNs);
        }
        return Outcome.error(code != null ? code : "HTTP_" + status, status, latencyNs);
    }

    /**
     * Pulls the structured code out of whichever envelope answered.
     *
     * <p>Order matters: {@code code} is the canonical {@code ApiError} field, {@code declineReason} is
     * the wallet endpoint's, and {@code error} is the Spring Boot default error body's — which is a
     * reason phrase, not a code, so it is taken last and normalised.
     */
    static String extractCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            for (String field : new String[] {"code", "declineReason", "decline_reason", "errorCode"}) {
                String value = root.path(field).asText("");
                if (!value.isBlank()) {
                    return normalise(value);
                }
            }
            String springError = root.path("error").asText("");
            if (!springError.isBlank()) {
                return normalise(springError);
            }
        } catch (Exception e) {
            // Not JSON (an HTML error page from a proxy, a truncated body). Deliberately silent: the
            // caller falls back to HTTP_<status>, which is still an honest, aggregatable code.
            return null;
        }
        return null;
    }

    /** {@code Unprocessable Entity} → {@code UNPROCESSABLE_ENTITY}, so every tally key has one shape. */
    private static String normalise(String value) {
        String upper = value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return upper.length() > 64 ? upper.substring(0, 64) : upper;
    }
}
