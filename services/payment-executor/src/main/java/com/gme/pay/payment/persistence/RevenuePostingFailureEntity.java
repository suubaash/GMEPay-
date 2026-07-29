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
    }

    /** Records another observed failure of the same posting: bump the count, refresh the error. */
    public void recordAnotherFailure(String payload, String lastError, Instant now) {
        this.attempts = this.attempts + 1;
        this.payload = payload;
        this.lastError = lastError;
        this.status = STATUS_PENDING;
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
}
