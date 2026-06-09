package com.gme.pay.ledger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code journals} table.
 *
 * <p>This is a persistence-only type — kept separate from the rich domain model
 * {@link com.gme.pay.ledger.domain.model.Journal} so that the domain stays free of
 * persistence annotations and constructors.
 *
 * <p>Mapping is intentionally minimal: a journal's individual entries live in
 * {@link LedgerEntryEntity}, joined by {@code journal_id} (no JPA association — kept
 * as a foreign reference to keep the persistence layer simple).
 */
@Entity
@Table(name = "journals")
public class JournalEntity {

    @Id
    @Column(name = "journal_id", length = 64, nullable = false)
    private String journalId;

    @Column(name = "reference", length = 64)
    private String reference;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    /** Required by JPA. */
    protected JournalEntity() {
    }

    public JournalEntity(String journalId, String reference, Instant postedAt) {
        this.journalId = Objects.requireNonNull(journalId, "journalId required");
        this.reference = reference;
        this.postedAt = Objects.requireNonNull(postedAt, "postedAt required");
    }

    public String getJournalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }
}
