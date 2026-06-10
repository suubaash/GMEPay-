package com.gme.pay.payment.persistence;

import com.gme.pay.payment.domain.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Persisted partner idempotency key (17.2-G08, table {@code idempotency_keys}).
 *
 * <p>A retried {@code POST /v1/payments} carrying the same (partner, key) pair replays the
 * recorded {@link #getResponseBody() response snapshot} instead of re-executing the payment.
 * Uniqueness is scoped per partner — enforced by the DB constraint
 * {@code uq_idempotency_partner_key} (Flyway V002), never by application checks alone.
 */
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_partner_key",
                columnNames = {"partner_id", "idempotency_key"}))
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partner_id", nullable = false)
    private long partnerId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "txn_ref", length = 64)
    private String txnRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_status", length = 24)
    private PaymentStatus responseStatus;

    /** Serialized response snapshot replayed on retry (TEXT column; money stays string-typed JSON). */
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** JPA only. */
    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(long partnerId,
                                   String idempotencyKey,
                                   String requestHash,
                                   Instant createdAt) {
        this.partnerId = partnerId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    // ---- getters ----

    public Long getId() {
        return id;
    }

    public long getPartnerId() {
        return partnerId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public PaymentStatus getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    // ---- setters for completion fields ----

    public void setTxnRef(String txnRef) {
        this.txnRef = txnRef;
    }

    /** Records the outcome to be replayed on a retried request. */
    public void recordOutcome(PaymentStatus responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
