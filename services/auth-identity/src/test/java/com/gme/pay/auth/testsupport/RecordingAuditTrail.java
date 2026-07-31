package com.gme.pay.auth.testsupport;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.auth.audit.AuthAuditTrail;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-memory {@link AuthAuditTrail} for the plain-JUnit and {@code @DataJpaTest} slices: captures
 * what each service <i>would</i> have written, so those tests stay context-light and can assert on
 * the audit contract (event type, chain key, redaction) without a database, a hash chain, or a
 * transaction manager in the picture.
 *
 * <p>The durable half — that the rows actually land, seal into the chain, and survive a rolled-back
 * rejection — is proved separately against a real datasource in
 * {@code com.gme.pay.auth.audit.AuthAuditTrailDbTest}. A fake alone would let "we recorded it" pass
 * while nothing reached the table, so both halves exist deliberately.
 *
 * <p>Records the {@code rejection} flag per call because the distinction (own transaction vs the
 * caller's) is a security property, not an implementation detail: a test asserting that a failed
 * login is audited must be able to assert it was audited on the path that survives rollback.
 */
public class RecordingAuditTrail implements AuthAuditTrail {

    /** One captured write. */
    public record Entry(String aggregateType, String aggregateId, String eventType,
                        String beforeJson, String afterJson, boolean rejection) {

        /** True when either payload contains {@code needle} — the redaction assertion. */
        public boolean mentions(String needle) {
            return (beforeJson != null && beforeJson.contains(needle))
                    || (afterJson != null && afterJson.contains(needle));
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * The actor this fake reports. Defaults to {@link AuditActors#UNATTRIBUTED} — the honest value
     * for a call with no HTTP request bound, and deliberately NOT a plausible operator name, so a
     * test cannot accidentally assert attributed behaviour that production would not produce.
     */
    private String actor = AuditActors.UNATTRIBUTED;

    @Override
    public void record(String aggregateType, String aggregateId, String eventType,
                       String beforeJson, String afterJson) {
        entries.add(new Entry(aggregateType, aggregateId, eventType, beforeJson, afterJson, false));
    }

    @Override
    public void recordRejection(String aggregateType, String aggregateId, String eventType,
                                String detailsJson) {
        entries.add(new Entry(aggregateType, aggregateId, eventType, null, detailsJson, true));
    }

    @Override
    public String currentActor() {
        return actor;
    }

    /** Override the reported actor (used by the {@code granted_by} attribution test). */
    public void actingAs(String actorId) {
        this.actor = actorId;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Entry> ofType(String eventType) {
        return entries.stream().filter(e -> eventType.equals(e.eventType())).toList();
    }

    public Entry only(String eventType) {
        List<Entry> matches = ofType(eventType);
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one " + eventType + " audit entry, got "
                    + matches.size() + " (all entries: " + entries + ")");
        }
        return matches.get(0);
    }

    public void clear() {
        entries.clear();
    }

    /**
     * Assert that no captured payload contains any of the given material, in any case. The
     * case-insensitive comparison is deliberate: a call site that lower-cased a token before
     * logging it has still logged the token.
     */
    public void assertNeverRecorded(String... forbiddenMaterial) {
        for (String secret : forbiddenMaterial) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            String needle = secret.toLowerCase(Locale.ROOT);
            for (Entry e : entries) {
                String haystack = ((e.beforeJson() == null ? "" : e.beforeJson())
                        + (e.afterJson() == null ? "" : e.afterJson())).toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    throw new AssertionError("audit entry " + e.eventType()
                            + " contains credential material: " + e);
                }
            }
        }
    }
}
