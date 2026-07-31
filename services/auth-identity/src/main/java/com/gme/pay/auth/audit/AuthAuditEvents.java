package com.gme.pay.auth.audit;

/**
 * The closed vocabulary of {@code audit_log.aggregate_type} and {@code audit_log.event_type}
 * values this service writes (gap T5-1 / CISO §9).
 *
 * <h2>Why a constants class rather than string literals at the call sites</h2>
 *
 * <p>An audit trail is only queryable if the verbs are stable. A regulator's question is
 * "show me every permission grant in the period", which is a {@code WHERE event_type = ?} —
 * and a trail where one call site writes {@code RBAC_GRANT} and another writes
 * {@code RBAC_PERMISSION_GRANTED} silently answers that question wrong. Both columns are
 * {@code VARCHAR(64)}, so the constants are also the place the length is guaranteed
 * ({@link AuthAuditEventsSelfCheck} asserts it in the test source set).
 *
 * <h2>Choosing the aggregate id</h2>
 *
 * <p>The hash chain is per {@code (aggregate_type, aggregate_id)}, so the id decides which
 * events an investigator can verify and read as one story. The ids here are grouped by the
 * <i>subject of the authority</i>, not by the request:
 *
 * <ul>
 *   <li>{@link #ROLE} is keyed by role code — so a role's whole authority history (created,
 *       then every permission granted or revoked on it) is one chain. "Who granted {@code *}
 *       to {@code HUB_ADMIN}" is one chain read.</li>
 *   <li>{@link #PRINCIPAL} is keyed by {@code principal:<id>} — so one operator's assignment
 *       history is one chain.</li>
 *   <li>{@link #API_KEY} is keyed by the PUBLIC key id — the credential's own lifecycle
 *       (issued → revoked) is one chain. {@code api_keys.api_key} is {@code VARCHAR(64)}, the
 *       same width as {@code aggregate_id}, so it fits exactly.</li>
 *   <li>{@link #SESSION} is keyed by {@code partner:<id>} when the credential resolved, and by
 *       {@code apikey:<prefix>} when it did not — a brute-force against an unknown key still
 *       accumulates into one readable chain instead of scattering into one chain per attempt.</li>
 *   <li>{@link #TOKEN} is keyed by {@code sub:<subject>}, falling back to
 *       {@link #UNKNOWN_SUBJECT} for a token so malformed that no subject could be read.
 *       One bucket, not one chain per garbage token.</li>
 * </ul>
 */
public final class AuthAuditEvents {

    private AuthAuditEvents() {}

    // ------------------------------------------------------------------ aggregate types

    /** Partner HMAC request-signature verification outcomes ({@code AuthVerificationService}). */
    public static final String SESSION = "auth_session";

    /** Platform capability token issue / verify outcomes ({@code JwtTokenService}). */
    public static final String TOKEN = "auth_token";

    /** One partner machine credential, keyed by its PUBLIC key id. */
    public static final String API_KEY = "api_key";

    /** A {@code (partnerCode, environment)} principal's credential-rotation events. */
    public static final String API_KEY_PRINCIPAL = "api_key_principal";

    /** The permission catalogue. */
    public static final String PERMISSION = "rbac_permission";

    /** One role: its creation and every permission granted to / revoked from it. */
    public static final String ROLE = "rbac_role";

    /** One principal: every role assignment and revocation. */
    public static final String PRINCIPAL = "rbac_principal";

    /** Typed permission constraints attached to a scope. */
    public static final String CONSTRAINT = "rbac_constraint";

    // ---------------------------------------------------------------------- event types

    /** A partner request signature was accepted. Off by default — see {@code AuthVerificationService}. */
    public static final String AUTH_VERIFY_SUCCEEDED = "AUTH_VERIFY_SUCCEEDED";

    /**
     * A partner request signature was REJECTED (unknown key / timestamp drift / replayed nonce /
     * bad signature). Always recorded, and recorded so that it survives the rejection path's
     * rollback.
     */
    public static final String AUTH_VERIFY_FAILED = "AUTH_VERIFY_FAILED";

    /**
     * A platform capability token was minted. A privileged success: whoever holds one of these
     * tokens is, to every service that trusts a GME signature, the subject named in it. Always
     * recorded.
     */
    public static final String TOKEN_ISSUED = "TOKEN_ISSUED";

    /** A token-issue request was refused (no subject). */
    public static final String TOKEN_ISSUE_REJECTED = "TOKEN_ISSUE_REJECTED";

    /** A presented token failed verification — forged/malformed signature, or expired. */
    public static final String TOKEN_VERIFY_FAILED = "TOKEN_VERIFY_FAILED";

    /** A partner machine credential was minted. */
    public static final String API_KEY_ISSUED = "API_KEY_ISSUED";

    /** A partner machine credential was revoked. */
    public static final String API_KEY_REVOKED = "API_KEY_REVOKED";

    /** All active credentials on a principal were revoked and one fresh credential issued. */
    public static final String API_KEY_ROTATED = "API_KEY_ROTATED";

    /** A permission was added to the catalogue. */
    public static final String PERMISSION_CREATED = "RBAC_PERMISSION_CREATED";

    /** A role was created (possibly with an initial permission set). */
    public static final String ROLE_CREATED = "RBAC_ROLE_CREATED";

    /** A permission was granted to a role — the "who granted {@code *}" event. */
    public static final String PERMISSION_GRANTED = "RBAC_PERMISSION_GRANTED";

    /** A permission was withdrawn from a role. */
    public static final String PERMISSION_REVOKED = "RBAC_PERMISSION_REVOKED";

    /** A role was assigned to a principal (optionally time-boxed). */
    public static final String ROLE_ASSIGNED = "RBAC_ROLE_ASSIGNED";

    /** A principal's role assignment was revoked. */
    public static final String ROLE_UNASSIGNED = "RBAC_ROLE_UNASSIGNED";

    /** A typed constraint was attached to a scope. */
    public static final String CONSTRAINT_CREATED = "RBAC_CONSTRAINT_CREATED";

    /** A typed constraint was deactivated (soft-deleted; the row is retained). */
    public static final String CONSTRAINT_DEACTIVATED = "RBAC_CONSTRAINT_DEACTIVATED";

    // --------------------------------------------------------------- aggregate-id helpers

    /** {@code aggregate_id} is {@code VARCHAR(64)}. */
    public static final int MAX_AGGREGATE_ID_LEN = 64;

    /**
     * Bucket id for token events whose subject could not be established (a signature that did
     * not verify carries no trustworthy subject at all). Deliberately one shared chain rather
     * than one chain per unreadable token: a thousand single-row chains cannot be verified as a
     * sequence and hide the very pattern — a sweep of forged tokens — the rows exist to reveal.
     */
    public static final String UNKNOWN_SUBJECT = "unknown-subject";

    /** {@code partner:<id>} — the session chain for a resolved partner credential. */
    public static String partnerAggregate(Long partnerId) {
        return clamp("partner:" + partnerId);
    }

    /**
     * {@code apikey:<prefix>} — the session chain for an api key that did NOT resolve. Only the
     * leading, non-secret portion of the presented key identifier is used: the api key is a
     * public identifier rather than secret material, but an unresolved one is attacker-supplied
     * free text and there is no reason to store more of it than identifies the attempt.
     */
    public static String unknownApiKeyAggregate(String presentedApiKey) {
        String prefix = AuditPayload.leading(presentedApiKey, 20);
        return clamp("apikey:" + (prefix == null ? "none" : prefix));
    }

    /** {@code sub:<subject>} — the token chain for a named subject. */
    public static String subjectAggregate(String subject) {
        if (subject == null || subject.isBlank()) {
            return UNKNOWN_SUBJECT;
        }
        return clamp("sub:" + subject.trim());
    }

    /** {@code principal:<id>} — the assignment chain for one principal. */
    public static String principalAggregate(Long principalId) {
        return clamp("principal:" + principalId);
    }

    /** {@code <scopeType>:<scopeId>} — the constraint chain for one scope row. */
    public static String scopeAggregate(String scopeType, Long scopeId) {
        return clamp(scopeType + ":" + scopeId);
    }

    /**
     * Clamp to {@link #MAX_AGGREGATE_ID_LEN}. Truncation keeps the PREFIX so the namespace
     * ({@code partner:}, {@code sub:}, …) is never the part that gets lost — an over-long
     * subject must not be able to collide with a different namespace.
     */
    public static String clamp(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            return UNKNOWN_SUBJECT;
        }
        String v = aggregateId.trim();
        return v.length() <= MAX_AGGREGATE_ID_LEN ? v : v.substring(0, MAX_AGGREGATE_ID_LEN);
    }
}
