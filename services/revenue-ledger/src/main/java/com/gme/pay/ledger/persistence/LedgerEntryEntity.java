package com.gme.pay.ledger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * JPA entity for the {@code ledger_entries} table — one row per debit/credit line.
 *
 * <p>Kept separate from the domain {@link com.gme.pay.ledger.domain.model.LedgerEntry}
 * (which is value-typed and immutable) so the domain model is free of persistence concerns.
 *
 * <p>{@code amount} is stored as {@code NUMERIC(20,8)} per {@code MONEY_CONVENTION.md}.
 * {@code entry_type} is stored as {@code VARCHAR(8)} holding "DEBIT" or "CREDIT".
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "journal_id", length = 64, nullable = false)
    private String journalId;

    @Column(name = "account", length = 64, nullable = false)
    private String account;

    @Column(name = "amount", precision = 20, scale = 8, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "entry_type", length = 8, nullable = false)
    private String entryType;

    @Column(name = "reference", length = 64, nullable = false)
    private String reference;

    /** Required by JPA. */
    protected LedgerEntryEntity() {
    }

    public LedgerEntryEntity(String journalId, String account, BigDecimal amount,
                             String currency, String entryType, String reference) {
        this.journalId = Objects.requireNonNull(journalId, "journalId required");
        this.account = Objects.requireNonNull(account, "account required");
        this.amount = Objects.requireNonNull(amount, "amount required");
        this.currency = Objects.requireNonNull(currency, "currency required");
        this.entryType = Objects.requireNonNull(entryType, "entryType required");
        this.reference = Objects.requireNonNull(reference, "reference required");
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJournalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }
}
