package com.gme.pay.bff.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One audited operator action — who did what, to what, when, and why (table
 * {@code operator_action_audit}, Flyway V002).
 *
 * <p>Replaces a {@code CopyOnWriteArrayList} inside {@code StubOperatorActionAuditClient}, which was
 * the live implementation in <b>every</b> environment because its selector property was set nowhere
 * and it carried {@code matchIfMissing = true}. Consequences of that, now fixed: every replica minted
 * its own {@code OA-1, OA-2, …} so audit ids collided; the record of who paused the platform was split
 * across replicas and lost on restart; and {@code recordDurable} — the fail-closed write that is
 * supposed to <em>block</em> a money/state-affecting operator action with no audit trail — could never
 * fail, so the guarantee was decorative.
 *
 * <p>Append-only: there is no setter and no update path. The id comes from one database sequence, so
 * the {@code OA-<id>} an operator sees is unique fleet-wide.
 */
@Entity
@Table(name = "operator_action_audit")
public class OperatorActionAuditEntity {

    private static final int MAX_ACTION_LEN = 64;
    private static final int MAX_TARGET_LEN = 255;
    private static final int MAX_ACTOR_LEN = 128;
    private static final int MAX_REASON_LEN = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "action", nullable = false, length = MAX_ACTION_LEN)
    private String action;

    @Column(name = "target", nullable = false, length = MAX_TARGET_LEN)
    private String target;

    @Column(name = "actor", nullable = false, length = MAX_ACTOR_LEN)
    private String actor;

    @Column(name = "reason", length = MAX_REASON_LEN)
    private String reason;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected OperatorActionAuditEntity() {
        // JPA
    }

    public OperatorActionAuditEntity(String action, String target, String actor, String reason,
                                     Instant recordedAt) {
        // The three NOT NULL columns get an explicit stand-in rather than a constraint violation: an
        // audit write must not fail because a caller passed a null target. Losing the record is worse
        // than recording it with "unknown" — and for recordDurable a violation would BLOCK the
        // operator action, so a null here would become an outage.
        this.action = truncate(blankToDefault(action, "unknown"), MAX_ACTION_LEN);
        this.target = truncate(blankToDefault(target, "unknown"), MAX_TARGET_LEN);
        this.actor = truncate(blankToDefault(actor, "unknown"), MAX_ACTOR_LEN);
        this.reason = truncate(reason, MAX_REASON_LEN);
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    private static String blankToDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
