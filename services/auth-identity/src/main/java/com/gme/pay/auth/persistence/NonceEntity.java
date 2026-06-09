package com.gme.pay.auth.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for the {@code used_nonces} table (V001__create_nonces.sql).
 *
 * Each row represents a (partnerId, nonce) pair that has been observed by
 * {@link com.gme.pay.auth.domain.NonceStore#checkAndSet}. Existence of a row
 * for the given nonce is the replay-detection signal.
 *
 * Per SEC-09 §3.3 / API-05 §3.6 — see V001__create_nonces.sql.
 */
@Entity
@Table(name = "used_nonces")
public class NonceEntity {

    /** Nonce value as supplied in the X-Nonce header — primary key. */
    @Id
    @Column(name = "nonce", length = 64, nullable = false)
    private String nonce;

    /** Owning partner identifier (string form matches PartnerCredentialPort). */
    @Column(name = "partner_id", length = 32, nullable = false)
    private String partnerId;

    /** Server-side instant at which the nonce was first observed. */
    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    /** Default constructor required by JPA. */
    protected NonceEntity() {
    }

    public NonceEntity(String nonce, String partnerId, Instant usedAt) {
        this.nonce = nonce;
        this.partnerId = partnerId;
        this.usedAt = usedAt;
    }

    public String getNonce() {
        return nonce;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
