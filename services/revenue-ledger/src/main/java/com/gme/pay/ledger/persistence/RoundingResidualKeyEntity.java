package com.gme.pay.ledger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Idempotency-guard row for a posted rounding residual (see {@code V006}).
 *
 * <p>One row per {@code reference} for which a {@code REVENUE_ROUNDING} journal has been posted. The
 * {@code reference} is the primary key, so a concurrent second rounding post for the same reference
 * fails on the PK constraint rather than double-booking — the database backstop behind the
 * application-level pre-check in {@code LedgerPostingService.postRoundingResidual}.
 *
 * <p>Only rounding-residual journals write here; revenue-capture / fee-share / reversal journals
 * carrying the same {@code reference} on other accounts are unaffected.
 */
@Entity
@Table(name = "rounding_residual_keys")
public class RoundingResidualKeyEntity {

    @Id
    @Column(name = "reference", length = 64, nullable = false)
    private String reference;

    @Column(name = "journal_id", length = 64, nullable = false)
    private String journalId;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    /** Required by JPA. */
    protected RoundingResidualKeyEntity() {
    }

    public RoundingResidualKeyEntity(String reference, String journalId, Instant postedAt) {
        this.reference = Objects.requireNonNull(reference, "reference required");
        this.journalId = Objects.requireNonNull(journalId, "journalId required");
        this.postedAt = Objects.requireNonNull(postedAt, "postedAt required");
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getJournalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }
}
