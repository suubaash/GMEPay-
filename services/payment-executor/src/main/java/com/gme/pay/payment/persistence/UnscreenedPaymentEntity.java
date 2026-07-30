package com.gme.pay.payment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One aggregate of payments accepted <b>without</b> a sanctions/PEP screening of a given party (table
 * {@code unscreened_payments}, Flyway V010 — gap <b>T5-3</b>).
 *
 * <p>Keyed by {@code (gap_date, reason_code, party_role, provider_id, partner_ref)} with an incremented
 * {@code payment_count}. See the V010 header for why this is an aggregate rather than a row per payment,
 * and why it holds no counterparty PII.
 *
 * <p><b>A row here is a finding, not a success record.</b> The absence of rows is ambiguous on its own
 * — it means either "everything was screened" or "the gate is not wired / no traffic" — which is why
 * the read surface always returns the configured provider and its authoritativeness alongside the
 * counts.
 */
@Entity
@Table(name = "unscreened_payments")
public class UnscreenedPaymentEntity {

    /** Value written to {@code partner_ref} when the entry point did not resolve a partner code. */
    public static final String UNKNOWN_PARTNER = "unknown";

    private static final int MAX_REF_LEN = 128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gap_date", nullable = false)
    private LocalDate gapDate;

    /** {@code UnscreenedReason} name — CHECK-constrained in V010. */
    @Column(name = "reason_code", nullable = false, length = 32)
    private String reasonCode;

    /** {@code PaymentParty} name — CHECK-constrained in V010. */
    @Column(name = "party_role", nullable = false, length = 16)
    private String partyRole;

    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    @Column(name = "payment_count", nullable = false)
    private long paymentCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "first_payment_ref", length = MAX_REF_LEN)
    private String firstPaymentRef;

    @Column(name = "last_payment_ref", length = MAX_REF_LEN)
    private String lastPaymentRef;

    @Column(name = "partner_ref", nullable = false, length = MAX_REF_LEN)
    private String partnerRef;

    protected UnscreenedPaymentEntity() {
        // JPA
    }

    /** First occurrence on this date for this (reason, party, provider, partner): count starts at 1. */
    public UnscreenedPaymentEntity(LocalDate gapDate,
                                   String reasonCode,
                                   String partyRole,
                                   String providerId,
                                   String partnerRef,
                                   String paymentRef,
                                   Instant seenAt) {
        this.gapDate = gapDate;
        this.reasonCode = reasonCode;
        this.partyRole = partyRole;
        this.providerId = providerId;
        this.partnerRef = partnerRef == null || partnerRef.isBlank()
                ? UNKNOWN_PARTNER : truncate(partnerRef);
        this.paymentCount = 1L;
        this.firstSeenAt = seenAt;
        this.lastSeenAt = seenAt;
        this.firstPaymentRef = truncate(paymentRef);
        this.lastPaymentRef = truncate(paymentRef);
    }

    public Long getId() {
        return id;
    }

    public LocalDate getGapDate() {
        return gapDate;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getPartyRole() {
        return partyRole;
    }

    public String getProviderId() {
        return providerId;
    }

    public long getPaymentCount() {
        return paymentCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getFirstPaymentRef() {
        return firstPaymentRef;
    }

    public String getLastPaymentRef() {
        return lastPaymentRef;
    }

    public String getPartnerRef() {
        return partnerRef;
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= MAX_REF_LEN ? t : t.substring(0, MAX_REF_LEN);
    }
}
