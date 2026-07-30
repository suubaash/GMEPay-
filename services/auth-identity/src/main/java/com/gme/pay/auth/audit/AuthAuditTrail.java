package com.gme.pay.auth.audit;

/**
 * The narrow port through which auth-identity's business services write the audit trail
 * (gap T5-1 / CISO §9).
 *
 * <h2>Why two methods rather than one</h2>
 *
 * <p>The distinction is transactional, and it is the single most important thing about
 * auditing an authentication service.
 *
 * <ul>
 *   <li>{@link #record} joins the caller's transaction. That is what you want for a business
 *       write: an {@code API_KEY_ISSUED} row must commit if and only if the {@code api_keys}
 *       row commits, or the trail claims a credential exists that does not (or misses one that
 *       does).</li>
 *   <li>{@link #recordRejection} runs in its <b>own</b> transaction. A rejected login, a
 *       replayed nonce, a forged token — these happen on paths that abort, and an abort usually
 *       means a rollback. An audit row for a rejection that is rolled back <i>with</i> the
 *       rejection is not an audit row; it is the exact failure mode where the trail is empty
 *       precisely for the events a regulator or an incident responder came to read. So the
 *       rejection row is committed independently of whatever the business path decides to do.
 *       See {@code AuthAuditService.recordRejection} for the mechanics.</li>
 * </ul>
 *
 * <p>Keeping this a port rather than depending on the concrete service also keeps
 * {@code AuthVerificationServiceTest} and friends as plain, context-free JUnit tests: they pass
 * a recording fake and assert on what would have been written.
 *
 * <h2>Contract</h2>
 *
 * <p>Neither method throws on an audit-tier failure — a broken audit backend must not turn a
 * valid partner request into a 500 (see {@code AuditPublisher}'s contract). The one thing that
 * <i>does</i> throw is an unusable actor id, because that is a programming error in the caller
 * rather than backpressure. Callers therefore need no try/catch.
 *
 * <p>{@code beforeJson}/{@code afterJson} are the raw JSON documents built with
 * {@link AuditPayload}, which is also where the redaction rule lives: <b>no secret, API-key
 * secret, HMAC signature, password or raw token may appear in an audit row.</b>
 */
public interface AuthAuditTrail {

    /**
     * Record a successful state change, in the caller's transaction (commits or rolls back
     * together with the business write).
     *
     * @param aggregateType one of {@link AuthAuditEvents}' aggregate types.
     * @param aggregateId   the chain key; use {@link AuthAuditEvents}' {@code …Aggregate}
     *                      helpers so it stays inside {@code VARCHAR(64)}.
     * @param eventType     one of {@link AuthAuditEvents}' verbs.
     * @param beforeJson    prior state, or {@code null} for a creation.
     * @param afterJson     new state, or {@code null} for a deletion.
     */
    void record(String aggregateType, String aggregateId, String eventType,
                String beforeJson, String afterJson);

    /**
     * Record a rejection / failure in a transaction of its own, so the row survives the
     * rollback of whatever the caller was doing when it decided to reject.
     *
     * @param detailsJson what was attempted and by whom-claimed — never the credential itself.
     */
    void recordRejection(String aggregateType, String aggregateId, String eventType,
                         String detailsJson);

    /**
     * The actor the current request resolves to, in the {@code AuditActors} vocabulary — never
     * {@code null}, never blank, never the bare {@code "system"} literal.
     *
     * <p>Exposed on the port because two writes need the resolved identity as <i>data</i> and
     * not only as the {@code actor_id} column: {@code user_roles.granted_by} must record who
     * granted a role (it used to record a body-supplied string, defaulting to {@code "system"}),
     * and a rotation payload names the operator who triggered it.
     */
    String currentActor();
}
