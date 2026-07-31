package com.gme.pay.ledger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

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
 *
 * <h2>Why {@link Persistable} (gap T2-9)</h2>
 *
 * <p>The {@code reference} is an <b>assigned</b> identifier, so Spring Data's default
 * {@code isNew() == (id == null)} rule classified every save as an <em>update</em> and issued
 * {@code EntityManager.merge(…)}. Merge on an existing row is a SELECT + UPDATE: the documented
 * "second concurrent rounding post fails on the PK constraint" never happened — instead the guard row was
 * silently re-pointed at the newer journal, which is how a reversing journal that mirrored a
 * {@code REVENUE_ROUNDING} line could corrupt (rather than merely duplicate) the residual guard.
 * Declaring the row {@link #isNew() always new} until it has been loaded or persisted forces a real
 * {@code INSERT}, so the primary key is once again the backstop the migration comment claims it is.
 */
@Entity
@Table(name = "rounding_residual_keys")
public class RoundingResidualKeyEntity implements Persistable<String> {

    @Id
    @Column(name = "reference", length = 64, nullable = false)
    private String reference;

    /**
     * False once this instance represents a row that is already in the database (loaded via
     * {@code findById}, or just inserted). Not mapped — see the class javadoc.
     */
    @Transient
    private boolean persisted;

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

    // --- Persistable: force INSERT so the PK is a real concurrency backstop (T2-9) ---

    @Override
    public String getId() {
        return reference;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
