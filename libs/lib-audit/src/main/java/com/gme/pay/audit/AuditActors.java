package com.gme.pay.audit;

import java.util.Locale;

/**
 * The vocabulary of {@code audit_log.actor_id} values, and the rules that keep an audit
 * row's actor from being a lie.
 *
 * <h2>Why this class exists (gap T5-1 / CISO §9)</h2>
 *
 * <p>Until 2026-07-28 every config-registry write endpoint took the operator identity as
 * {@code @RequestHeader(value = "X-Actor", required = false)} and 24 service classes fell
 * back to {@code private static final String DEFAULT_ACTOR = "system"} when the header was
 * absent. Two things were wrong with that, and they compounded:
 *
 * <ol>
 *   <li>The header is <b>unauthenticated client input</b>. Nothing stamped, validated or
 *       stripped it (the api-gateway handles {@code X-Gme-*} and {@code X-Actor} is outside
 *       that set), so any caller with network reach could attribute a fee change, a
 *       credential rotation or a KYB verdict to an arbitrary operator name.</li>
 *   <li>Omitting the header produced the literal {@code "system"} — which is <b>also</b> the
 *       blanket carve-out in the 4-eyes CHECK constraint
 *       ({@code V005__change_request.sql}: {@code proposed_by = 'system' AND approved_by =
 *       'system'}). So "nobody told me who did this" and "the platform did this to itself
 *       with no human in the loop, and segregation-of-duties does not apply" were spelled
 *       the same way.</li>
 * </ol>
 *
 * <p>A regulator reading such a log cannot distinguish an attributed operator action from a
 * forged one from an unattributed one. That is the definition of a non-regulator-grade audit
 * trail, and no amount of hash-chaining fixes it — sealing a lie only proves the lie has not
 * been edited since.
 *
 * <h2>The four principal kinds</h2>
 *
 * <p>Every actor id now carries its own provenance in a namespace prefix, so the audit row
 * states not just <i>who</i> but <i>on what evidence</i>:
 *
 * <table border="1">
 *   <caption>Actor namespaces</caption>
 *   <tr><th>Shape</th><th>Meaning</th><th>Minted by</th></tr>
 *   <tr>
 *     <td>{@code alice@gme.com} (no prefix)</td>
 *     <td><b>Attested human.</b> The identity came out of a <i>verified</i> credential — a
 *         JWT subject validated by the receiving service, or a claim forwarded by a service
 *         that itself authenticated with the internal-auth secret. Bare (unprefixed) so the
 *         common, trustworthy case reads naturally in a report.</td>
 *     <td>{@link #attested(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code system:auto-suspend}</td>
 *     <td><b>Explicit system principal.</b> A genuine platform-initiated action with no human
 *         actor — a scheduler, a migration, an auto-suspend on prefunding breach. The
 *         component is <b>mandatory</b>: it is never "the platform", it is always a named
 *         piece of the platform.</td>
 *     <td>{@link #system(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code svc:ops-partner-bff}</td>
 *     <td><b>Service identity.</b> A trusted in-cluster caller proved itself with the
 *         internal-auth shared secret but forwarded no human principal. The <i>service</i>
 *         is attested; the human is unknown, and the row says so.</td>
 *     <td>{@link #service(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code unverified:alice}</td>
 *     <td><b>Claimed but unproven.</b> A name arrived on the wire with no verified credential
 *         behind it. It is recorded (throwing the claim away would lose forensic value) but
 *         it is <b>structurally distinguishable</b> from an attested principal, so it can
 *         never be read as, mistaken for, or joined against a real operator id.</td>
 *     <td>{@link #unverified(String)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code unattributed}</td>
 *     <td><b>Nothing was claimed and nothing was proven.</b> The honest spelling of the old
 *         silent {@code "system"} default.</td>
 *     <td>{@link #UNATTRIBUTED}</td>
 *   </tr>
 * </table>
 *
 * <h2>The bare {@code "system"} literal is rejected</h2>
 *
 * <p>{@link #requireAttributable(String)} — called from {@link AuditEvent#newEvent} so it
 * cannot be bypassed by any writer — rejects {@code null}, blank, and the bare literal
 * {@code "system"} in any case. The rejection is deliberately loud rather than a silent
 * upgrade to {@code system:unknown}: a caller reaching that branch has a hole in its identity
 * derivation, and quietly minting a plausible principal is exactly the failure mode this gap
 * is about. Callers with a genuine system action name their component; callers who have lost
 * the identity use {@link #UNATTRIBUTED} and it shows up in a report.
 *
 * <p>Note that the 4-eyes carve-out in {@code V005} keys off the bare literal. Because no
 * writer can mint that literal any more, a header-less propose + header-less approve can no
 * longer self-approve through the carve-out — the two writes now land as {@code unattributed}
 * (or {@code unverified:…}), which the CHECK does not exempt. Removing the carve-out from the
 * constraint itself belongs to the segregation-of-duties gap (T5-10), not here.
 */
public final class AuditActors {

    /** {@code actor_id} is {@code VARCHAR(64)}; every factory clamps to this. */
    public static final int MAX_LEN = 64;

    /** Prefix for an explicit, named system principal. */
    public static final String SYSTEM_PREFIX = "system:";

    /** Prefix for an attested service (internal-auth) identity carrying no human principal. */
    public static final String SERVICE_PREFIX = "svc:";

    /** Prefix for a name that was claimed on the wire but backed by no verified credential. */
    public static final String UNVERIFIED_PREFIX = "unverified:";

    /** Nothing claimed, nothing proven. The honest replacement for the old {@code "system"} default. */
    public static final String UNATTRIBUTED = "unattributed";

    /**
     * The legacy blanket literal. Retained as a named constant <b>only</b> so the rejection
     * check and the historical-row readers can refer to it; no writer may mint it.
     */
    public static final String LEGACY_SYSTEM = "system";

    private AuditActors() {}

    /**
     * An operator identity that came out of a verified credential (a JWT subject validated by
     * the receiving service, or forwarded by an internal-auth-authenticated service).
     *
     * @throws IllegalArgumentException when {@code subject} is null/blank, or would collide
     *         with one of the reserved namespaces (a token whose subject is literally
     *         {@code system:foo} must not be able to impersonate a system principal).
     */
    public static String attested(String subject) {
        String s = trimToNull(subject);
        if (s == null) {
            throw new IllegalArgumentException(
                    "attested actor requires a non-blank verified subject");
        }
        if (isReserved(s)) {
            throw new IllegalArgumentException(
                    "verified subject collides with a reserved audit-actor namespace: " + s);
        }
        return clamp(s);
    }

    /**
     * An explicit system principal for a platform-initiated action with no human actor.
     *
     * @param component the named component taking the action — {@code "auto-suspend"},
     *                  {@code "settlement-scheduler"}, {@code "migration:v042"}. Mandatory.
     */
    public static String system(String component) {
        String c = trimToNull(component);
        if (c == null) {
            throw new IllegalArgumentException(
                    "a system actor must name its component (e.g. AuditActors.system(\"auto-suspend\")) "
                            + "— an unnamed system principal is indistinguishable from a lost identity, "
                            + "use AuditActors.UNATTRIBUTED for that");
        }
        return clamp(SYSTEM_PREFIX + c.toLowerCase(Locale.ROOT));
    }

    /** An attested service identity (internal-auth verified) with no human principal behind it. */
    public static String service(String serviceName) {
        String s = trimToNull(serviceName);
        if (s == null) {
            throw new IllegalArgumentException("a service actor must name the service");
        }
        return clamp(SERVICE_PREFIX + s.toLowerCase(Locale.ROOT));
    }

    /**
     * A name claimed on the wire with no verified credential behind it. Never returns an
     * attested-looking value: a claim of {@code "system:auto-suspend"} becomes
     * {@code "unverified:system:auto-suspend"}, and a blank claim becomes
     * {@link #UNATTRIBUTED}.
     */
    public static String unverified(String claimed) {
        String c = trimToNull(claimed);
        if (c == null) {
            return UNATTRIBUTED;
        }
        if (c.startsWith(UNVERIFIED_PREFIX)) {
            return clamp(c);
        }
        return clamp(UNVERIFIED_PREFIX + c);
    }

    /** True when the value is one of the platform's reserved namespaces or the legacy literal. */
    public static boolean isReserved(String actorId) {
        if (actorId == null) {
            return false;
        }
        String a = actorId.trim();
        return a.startsWith(SYSTEM_PREFIX)
                || a.startsWith(SERVICE_PREFIX)
                || a.startsWith(UNVERIFIED_PREFIX)
                || a.equals(UNATTRIBUTED)
                || a.equalsIgnoreCase(LEGACY_SYSTEM);
    }

    /**
     * True when this actor id represents an identity backed by a verified credential — i.e.
     * an attested human or a named system/service principal. {@code unverified:*} and
     * {@code unattributed} are false. Reporting and 4-eyes logic should key off this rather
     * than string-matching prefixes.
     */
    public static boolean isAttributable(String actorId) {
        String a = trimToNull(actorId);
        if (a == null) {
            return false;
        }
        return !a.startsWith(UNVERIFIED_PREFIX)
                && !a.equals(UNATTRIBUTED)
                && !a.equalsIgnoreCase(LEGACY_SYSTEM);
    }

    /** True when this row was written by a named system principal ({@code system:<component>}). */
    public static boolean isSystem(String actorId) {
        String a = trimToNull(actorId);
        return a != null && a.startsWith(SYSTEM_PREFIX);
    }

    /**
     * True only for an <b>attested human</b> — the bare, unprefixed shape minted by
     * {@link #attested(String)}. Every other value in the vocabulary is false, including the
     * ones {@link #isAttributable(String)} accepts: {@code system:<component>} and
     * {@code svc:<name>} are attributable principals but they are not people.
     *
     * <p>Most audited writes do not need this distinction — a scheduler rotating a credential is
     * a perfectly good actor. It matters where the recorded act is a person <i>assuming
     * liability</i> rather than a person <i>making a change</i>. The motivating case is gap
     * T1-4's manual-KYB-SOP attestation (see {@code ManualScreeningAttestation}): a compliance
     * officer states that they personally performed a sanctions screening per a signed
     * procedure. A scheduler cannot make that statement, a shared service identity cannot make
     * it, and an unverified name is precisely the forgery T5-1 removed.
     */
    public static boolean isAttestedHuman(String actorId) {
        String a = trimToNull(actorId);
        return a != null && !isReserved(a);
    }

    /**
     * Require an {@link #isAttestedHuman(String) attested human} actor, for the small set of
     * writes that record a person taking personal responsibility for a compliance assertion.
     *
     * <p>Deliberately a separate check from {@link #requireAttributable(String)} rather than a
     * stricter mode of it: the platform must keep being able to audit system actions, and the
     * two questions ("is this attributable at all" / "is this a person who can be held to it")
     * have different answers for the same row.
     *
     * @return the trimmed actor id, when it is an attested human.
     * @throws IllegalArgumentException on null/blank, on any reserved namespace
     *         ({@code system:}, {@code svc:}, {@code unverified:}, {@link #UNATTRIBUTED}) and on
     *         the bare legacy {@code "system"} literal. The message names the value class it
     *         rejected so the caller can tell "nobody was authenticated" from "a service called
     *         this without forwarding a human".
     */
    public static String requireAttestedHuman(String actorId) {
        String a = trimToNull(actorId);
        if (a == null) {
            throw new IllegalArgumentException(
                    "this action must be attributed to a verified human operator, and no actor was "
                            + "resolved at all — use AuditActors.attested(verifiedSubject)");
        }
        if (!isAttestedHuman(a)) {
            throw new IllegalArgumentException(
                    "this action must be attributed to a verified human operator, but the resolved "
                            + "actor is '" + a + "'. "
                            + (a.startsWith(UNVERIFIED_PREFIX)
                                    ? "The name was claimed on the wire but no credential proved it"
                                    : a.equals(UNATTRIBUTED)
                                            ? "Nothing was claimed and nothing was proven"
                                            : a.startsWith(SERVICE_PREFIX)
                                                    ? "A trusted service called in without "
                                                            + "forwarding the human principal"
                                                    : "That is a platform principal, not a person")
                            + " — a person cannot be held to an assertion nobody can name.");
        }
        return a;
    }

    /**
     * Validate an actor id at the point of sealing. This is the single choke point that makes
     * the bare {@code "system"} literal unmintable.
     *
     * @return the (trimmed) actor id, when acceptable.
     * @throws IllegalArgumentException on null/blank, or on the bare legacy literal.
     */
    public static String requireAttributable(String actorId) {
        String a = trimToNull(actorId);
        if (a == null) {
            throw new IllegalArgumentException(
                    "audit actorId is required — use AuditActors.attested(verifiedSubject), "
                            + "AuditActors.system(component), AuditActors.service(name), "
                            + "AuditActors.unverified(claim) or AuditActors.UNATTRIBUTED");
        }
        if (a.equalsIgnoreCase(LEGACY_SYSTEM)) {
            throw new IllegalArgumentException(
                    "the bare actor literal \"system\" is not writable (gap T5-1): it was both the "
                            + "silent default for a missing X-Actor header and the 4-eyes carve-out, so "
                            + "it cannot mean anything. Use AuditActors.system(\"<component>\") for a "
                            + "real platform-initiated action, or AuditActors.UNATTRIBUTED when the "
                            + "identity is genuinely unknown");
        }
        return a;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Clamp to {@link #MAX_LEN}. Truncation keeps the <i>prefix</i> (the provenance) and drops
     * the tail of the name: an over-long value must not silently lose the fact that it was
     * unverified.
     */
    private static String clamp(String s) {
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN);
    }
}
