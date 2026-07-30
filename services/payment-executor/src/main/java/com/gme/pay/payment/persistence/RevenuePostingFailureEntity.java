package com.gme.pay.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A revenue-ledger posting that failed to reach revenue-ledger and is awaiting replay
 * (table {@code revenue_posting_failures}, Flyway V005 — gap T2-1 / CFO#6).
 *
 * <p>Revenue-ledger calls on the commit path are non-blocking by contract: a ledger outage must not fail
 * a payment whose money already moved. Before this row existed, that meant the posting was logged and
 * lost — the transaction's revenue silently never existed. Each failure is now durable, carrying the
 * exact request {@link #getPayload() payload}, so an ops job can re-POST it (every revenue-ledger
 * endpoint is idempotent on its reference/txnRef, so a replay cannot double-book).
 *
 * <p>Identity is {@code (reference, postingType)} — enforced by the DB constraint
 * {@code uq_revenue_posting_failures} — so repeated failures for the same posting bump
 * {@link #getAttempts() attempts} instead of piling up rows, and the PENDING set is exactly
 * "postings still missing from revenue-ledger".
 */
@Entity
@Table(name = "revenue_posting_failures",
        uniqueConstraints = @UniqueConstraint(name = "uq_revenue_posting_failures",
                columnNames = {"reference", "posting_type"}))
public class RevenuePostingFailureEntity {

    /** Awaiting replay. */
    public static final String STATUS_PENDING = "PENDING";
    /** Successfully replayed into revenue-ledger. */
    public static final String STATUS_REPLAYED = "REPLAYED";
    /** Given up on by an operator (kept for audit). */
    public static final String STATUS_ABANDONED = "ABANDONED";
    /**
     * Attempts exhausted, or the row is structurally unreplayable (no payload was captured) — the replay
     * job has stopped trying and has ALERTED (gap T2-5, Flyway V007).
     *
     * <p>Deliberately distinct from {@link #STATUS_ABANDONED}: abandoning is an operator's decision,
     * poisoning is the machine giving up. A POISON row is never retried again — that is the point, it must
     * not hammer a permanently-broken downstream — but it is also never silently forgotten: it stays on the
     * ops outstanding query and it raised a CRITICAL {@code ops.alert} when it got here.
     */
    public static final String STATUS_POISON = "POISON";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference", nullable = false, length = 128)
    private String reference;

    @Column(name = "posting_type", nullable = false, length = 32)
    private String postingType;

    /** The exact JSON request body that failed, so a replay needs no other source. */
    @Column(name = "payload")
    private String payload;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the replay job last called revenue-ledger for this row (V007). Null until first replayed. */
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    /**
     * When the replay job may next touch this row (V007). Null = due immediately.
     *
     * <p>The backoff is persisted rather than held in the job so a restart cannot reset every row's
     * schedule and re-storm a revenue-ledger that is still down.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** When the posting actually landed in revenue-ledger (V007). Null unless {@code REPLAYED}. */
    @Column(name = "replayed_at")
    private Instant replayedAt;

    /** JPA. */
    protected RevenuePostingFailureEntity() {
    }

    public RevenuePostingFailureEntity(String reference, String postingType, String payload,
                                       String lastError, Instant now) {
        this.reference = reference;
        this.postingType = postingType;
        this.payload = payload;
        this.lastError = lastError;
        this.attempts = 1;
        this.status = STATUS_PENDING;
        this.createdAt = now;
        this.updatedAt = now;
        // Due immediately: the posting is missing from the ledger from this instant on. The replay job
        // applies its own first-attempt delay, so "immediately" does not mean "in the same millisecond as
        // the failure that produced it".
        this.nextAttemptAt = now;
    }

    /**
     * Records another observed failure of the same posting on the HOT path: bump the count, refresh the
     * error, and push the next replay attempt out by {@code backoff}.
     *
     * <p><b>A terminal row is not resurrected.</b> If this posting has already been POISONed or ABANDONED,
     * the new occurrence is recorded (count + error + timestamps) but the status is left alone. Flipping it
     * back to PENDING would hand the replay job an unbounded budget: the same reference failing on the hot
     * path every day would re-arm the retries the bound exists to stop, and an operator's ABANDONED decision
     * would be silently undone by traffic.
     *
     * @param backoff how far out to push {@code nextAttemptAt}; the replay job owns the schedule
     */
    public void recordAnotherFailure(String payload, String lastError, Instant now,
                                     java.time.Duration backoff) {
        this.attempts = this.attempts + 1;
        this.payload = payload;
        this.lastError = lastError;
        if (!isTerminal()) {
            this.status = STATUS_PENDING;
            this.nextAttemptAt = backoff == null ? now : now.plus(backoff);
        }
        this.updatedAt = now;
    }

    /** True when this row will never be replayed again (landed, poisoned, or abandoned). */
    public boolean isTerminal() {
        return STATUS_REPLAYED.equals(status)
                || STATUS_POISON.equals(status)
                || STATUS_ABANDONED.equals(status);
    }

    /** True when the posting has no captured request body and therefore cannot be re-POSTed at all. */
    public boolean isReplayable() {
        return payload != null && !payload.isBlank();
    }

    /**
     * The replay job called revenue-ledger and it FAILED: bump the attempt count, stamp the error, and
     * schedule the next attempt.
     */
    public void recordReplayFailure(String error, Instant now, java.time.Duration backoff) {
        this.attempts = this.attempts + 1;
        this.lastError = error;
        this.status = STATUS_PENDING;
        this.lastAttemptAt = now;
        this.nextAttemptAt = backoff == null ? now : now.plus(backoff);
        this.updatedAt = now;
    }

    /** The posting reached revenue-ledger (or was already there — the endpoints are idempotent). */
    public void recordReplaySuccess(String note, Instant now) {
        this.status = STATUS_REPLAYED;
        this.lastError = note;
        this.lastAttemptAt = now;
        this.nextAttemptAt = null;
        this.replayedAt = now;
        this.updatedAt = now;
    }

    /**
     * Attempts exhausted, or the row cannot be replayed at all: stop trying. The caller ALERTS — a poison row
     * that nobody is told about is the silent loss this whole table exists to prevent.
     *
     * @param countAttempt true when revenue-ledger was actually called and refused, so the attempt counts;
     *                     false when the row was unreplayable and no call was made (counting a call that never
     *                     happened would make {@code attempts} a lie, and it is the audit trail of how many
     *                     times this posting was really pushed at the ledger)
     */
    public void poison(String reason, Instant now, boolean countAttempt) {
        if (countAttempt) {
            this.attempts = this.attempts + 1;
        }
        this.status = STATUS_POISON;
        this.lastError = reason;
        this.lastAttemptAt = countAttempt ? now : this.lastAttemptAt;
        this.nextAttemptAt = null;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getPostingType() {
        return postingType;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getReplayedAt() {
        return replayedAt;
    }
}
