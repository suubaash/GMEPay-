package com.gme.pay.registry.kyb;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.registry.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts the V042 KYB screening reclassification <b>on the audit record</b>, so it stops looking
 * like tampering — and reports the residual drift honestly where it cannot.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Gap T1-4 shipped {@code V042__partner_kyb_screening_provenance.sql}, which — deliberately,
 * once, and documented in its own header — <b>UPDATEs SCD-6 rows in place</b>: every
 * {@code partner_kyb} row whose {@code screening_status} was {@code CLEAR} but whose screening
 * came from a non-authoritative provider is rewritten to
 * {@code NOT_SCREENED_NO_PROVIDER}, with {@code reclassified_from} and
 * {@code reclassification_note} recording what it was and why it changed. That was the right
 * call: a stub keyword-match was being presented as a passed sanctions check, and leaving it
 * to read as {@code CLEAR} was the more dangerous option.
 *
 * <p>But the audit trail's last sealed AFTER snapshot for those partners still says
 * {@code CLEAR}. The T1-4 report flagged the consequence explicitly: <i>"the exit-gate tamper
 * check that compares partner_kyb rows against their sealed AFTER snapshots will report the
 * reclassified rows as drifted. Whoever runs that check needs to know V042 is the cause."</i>
 *
 * <p>The tempting fixes are both wrong:
 * <ul>
 *   <li><b>Re-seal the old audit rows</b> to match the new values — that is exactly the edit an
 *       attacker would make, and doing it ourselves means no future examiner can tell our
 *       migration from their attack. The hash chain would still verify and would have stopped
 *       meaning anything.</li>
 *   <li><b>Suppress the drift check</b> for these rows — silencing the one control that would
 *       notice an unauthorised status change, in order to hide an authorised one.</li>
 * </ul>
 *
 * <h2>What this does instead</h2>
 *
 * <p><b>Appends</b> one {@code PARTNER_KYB_RECLASSIFIED} audit event per reclassified partner,
 * attributed to the explicit system principal {@code system:migration-v042}. The event's BEFORE
 * is the status V042 found, its AFTER is the status V042 wrote, and its payload carries the
 * migration's own {@code reclassification_note}. After that:
 *
 * <ul>
 *   <li>the hash chain is untouched — nothing was rewritten, one row was added, which is the
 *       only legitimate way an append-only log ever changes;</li>
 *   <li>the drift between {@code partner_kyb} and the audit trail is <b>explained by the audit
 *       trail itself</b>, at the position in the chain where it happened;</li>
 *   <li>the actor is a named, non-spoofable system principal — not the bare {@code "system"}
 *       literal, which {@link AuditActors} now refuses precisely because it used to mean both
 *       "the platform did this" and "nobody told me who did this".</li>
 * </ul>
 *
 * <p>{@link #driftReport()} then reports each reclassified partner as {@code EXPLAINED} (a
 * reclassification event exists in its chain) or {@code UNEXPLAINED} (it does not) — so the
 * check still fails loudly for a status change that nothing accounts for, which is the whole
 * point of having it.
 *
 * <h2>Idempotency and ordering</h2>
 *
 * <p>Runs as an {@link ApplicationRunner} (after Flyway, after the context is up) and skips any
 * partner that already has a reclassification event, so restarts and re-deploys do not append
 * duplicates. It is a no-op on a database with no reclassified rows — which is every fresh
 * database and every test fixture, so it costs one indexed query at boot.
 *
 * <p>Deliberately <b>not</b> a Flyway Java callback: computing a correct hash-chain link
 * requires the application's canonicalisation and the tail of each chain, and running that
 * inside a migration would put the sealing logic on the schema-version timeline where it could
 * never be corrected. An append is safe to repeat; a migration is not.
 */
@Component
public class KybReclassificationProvenance implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KybReclassificationProvenance.class);

    /** Verb for the appended explanation event. */
    public static final String EVENT_TYPE_RECLASSIFIED = "PARTNER_KYB_RECLASSIFIED";

    /**
     * The named system principal for the V042 in-place correction. Not {@code "system"} — see
     * {@link AuditActors}. Encodes the migration version so the row points at the artefact that
     * caused it.
     */
    public static final String ACTOR = AuditActors.system("migration-v042");

    /**
     * Current KYB rows that V042 reclassified, with the partner code the audit chain is keyed
     * by. {@code k.superseded_at IS NULL} because SCD-6 keeps history: only the current KYB
     * row's status is what the platform acts on, and only that row's drift matters.
     *
     * <p>No such filter on {@code partners}: that table is bitemporal too (ADR-010 — one
     * current row plus N historicals per {@code partner_code}), and {@code partner_kyb.partner_id}
     * may point at whichever version was current when the KYB row was written. Filtering the
     * partner side to the current version would silently drop those partners from the report,
     * i.e. hide exactly the drift we are trying to account for. {@code partner_code} is stable
     * across versions, which is why the audit chain is keyed by it rather than by the surrogate.
     */
    private static final String RECLASSIFIED_SQL = """
            SELECT p.partner_code, k.reclassified_from, k.screening_status,
                   k.reclassification_note, k.screening_provider_id
              FROM partner_kyb k
              JOIN partners p ON p.id = k.partner_id
             WHERE k.reclassified_from IS NOT NULL
               AND k.superseded_at IS NULL
             ORDER BY p.partner_code
            """;

    private static final String EXISTING_EVENT_SQL = """
            SELECT count(*) FROM audit_log
             WHERE aggregate_type = ? AND aggregate_id = ? AND event_type = ?
            """;

    private final JdbcTemplate jdbc;
    private final AuditLogService auditLog;

    public KybReclassificationProvenance(JdbcTemplate jdbc, AuditLogService auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    @Override
    public void run(ApplicationArguments args) {
        int appended = sealReclassifications();
        if (appended > 0) {
            log.warn("audit: appended {} {} event(s) attributed to {} — V042 corrected these "
                            + "screening statuses in place, and the audit trail now says so at the "
                            + "point in each chain where it happened",
                    appended, EVENT_TYPE_RECLASSIFIED, ACTOR);
        }
    }

    /**
     * Append the missing reclassification events. Idempotent; returns how many were appended.
     *
     * <p>Not {@code @Transactional} at this level on purpose: each partner's append is
     * independent, and one partner whose chain cannot be written must not prevent the other
     * partners' explanations from being recorded. {@link AuditLogService#publish} is itself
     * transactional per row.
     */
    public int sealReclassifications() {
        List<Reclassified> rows = findReclassified();
        int appended = 0;
        for (Reclassified r : rows) {
            if (hasReclassificationEvent(r.partnerCode())) {
                continue;
            }
            try {
                auditLog.publish(
                        KybService.AGGREGATE_TYPE,
                        r.partnerCode(),
                        ACTOR,
                        // No client IP: there was no client. A fabricated one would be worse
                        // than none, and actorIp is sealed into the digest from CHAIN_V2 on.
                        null,
                        EVENT_TYPE_RECLASSIFIED,
                        beforeJson(r),
                        afterJson(r));
                appended++;
            } catch (RuntimeException e) {
                log.error("audit: failed to append {} for partner {} — the V042 drift for this "
                                + "partner remains UNEXPLAINED and will be reported as such",
                        EVENT_TYPE_RECLASSIFIED, r.partnerCode(), e);
            }
        }
        return appended;
    }

    /**
     * Report, per reclassified partner, whether the divergence between {@code partner_kyb} and
     * the audit trail is accounted for on the record.
     *
     * <p>This is the check the T1-4 report warned about, implemented so that it distinguishes
     * "a documented migration changed this" from "something changed this and nothing says
     * what". An {@code UNEXPLAINED} entry is a finding.
     */
    @Transactional(readOnly = true)
    public DriftReport driftReport() {
        List<DriftEntry> entries = new ArrayList<>();
        for (Reclassified r : findReclassified()) {
            boolean explained = hasReclassificationEvent(r.partnerCode());
            entries.add(new DriftEntry(
                    r.partnerCode(),
                    r.reclassifiedFrom(),
                    r.screeningStatus(),
                    r.screeningProviderId(),
                    explained,
                    explained
                            ? "explained by an appended " + EVENT_TYPE_RECLASSIFIED
                                    + " audit event attributed to " + ACTOR
                            : "NOT explained: no " + EVENT_TYPE_RECLASSIFIED + " event exists in "
                                    + "this partner's audit chain, so this status change is "
                                    + "unaccounted for",
                    r.reclassificationNote()));
        }
        long unexplained = entries.stream().filter(e -> !e.explained()).count();
        return new DriftReport(unexplained == 0, entries.size(), (int) unexplained, entries);
    }

    private List<Reclassified> findReclassified() {
        return jdbc.query(RECLASSIFIED_SQL, (rs, i) -> new Reclassified(
                rs.getString("partner_code"),
                rs.getString("reclassified_from"),
                rs.getString("screening_status"),
                rs.getString("reclassification_note"),
                rs.getString("screening_provider_id")));
    }

    private boolean hasReclassificationEvent(String partnerCode) {
        Integer count = jdbc.queryForObject(EXISTING_EVENT_SQL, Integer.class,
                KybService.AGGREGATE_TYPE, partnerCode, EVENT_TYPE_RECLASSIFIED);
        return count != null && count > 0;
    }

    /**
     * BEFORE payload: the status V042 found. Hand-rolled with a fixed key order for the same
     * reason {@link AuditLogService#canonicalPartnerJson} is — the bytes go into the hash, so
     * they must not depend on Jackson configuration.
     */
    private static byte[] beforeJson(Reclassified r) {
        return ("{\"screeningStatus\":" + json(r.reclassifiedFrom())
                + ",\"screeningProviderId\":" + json(r.screeningProviderId())
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] afterJson(Reclassified r) {
        return ("{\"screeningStatus\":" + json(r.screeningStatus())
                + ",\"screeningProviderId\":" + json(r.screeningProviderId())
                + ",\"reclassifiedBy\":\"V042\""
                + ",\"reclassificationNote\":" + json(r.reclassificationNote())
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
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
        return out.append('"').toString();
    }

    private record Reclassified(
            String partnerCode,
            String reclassifiedFrom,
            String screeningStatus,
            String reclassificationNote,
            String screeningProviderId) {
    }

    /**
     * One partner's V042 drift.
     *
     * @param explained true when an appended reclassification event accounts for the change.
     */
    public record DriftEntry(
            String partnerCode,
            String previousScreeningStatus,
            String currentScreeningStatus,
            String screeningProviderId,
            boolean explained,
            String verdict,
            String migrationNote) {
    }

    /**
     * @param allExplained  true when every reclassified row is accounted for on the record.
     *                      This is the flag an exit gate should assert, NOT "no drift exists" —
     *                      drift from a documented correction is expected and fine; drift
     *                      nothing explains is not.
     */
    public record DriftReport(
            boolean allExplained,
            int reclassifiedPartners,
            int unexplained,
            List<DriftEntry> entries) {
    }
}
