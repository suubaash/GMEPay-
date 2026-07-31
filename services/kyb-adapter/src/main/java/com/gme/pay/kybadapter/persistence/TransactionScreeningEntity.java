package com.gme.pay.kybadapter.persistence;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.TransactionScreeningEvidence;
import com.gme.pay.kyb.TransactionScreeningPolicy;
import com.gme.pay.kyb.UnscreenedReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One row of {@code transaction_screening} (Flyway V004): what was checked for ONE party of ONE
 * payment, by whom, when, and what the payment path did about it. Gap <b>T5-3</b>.
 *
 * <h2>The entity is a carrier, not the truth</h2>
 *
 * <p>This class deliberately has no business logic and no derived state. Every read goes through
 * {@link #toEvidence()}, which reconstructs a {@link TransactionScreeningEvidence} and therefore
 * re-applies that record's invariants — most importantly, a stored {@code (status='CLEAR',
 * provider_authoritative=false)} pair comes back as {@code NOT_SCREENED_NO_PROVIDER}. Callers must
 * never read the columns directly to answer "was this party screened"; they must call
 * {@link TransactionScreeningEvidence#completedScreening()}, which is derived and has no column.
 *
 * <p>That indirection is the whole reason the getters below are package-private where they can be. The
 * failure mode being designed out is a future caller writing {@code entity.getStatus() ==
 * Status.CLEAR}, which is exactly the shape gap T1-4 removed from the KYB path and which a plain JPA
 * entity invites.
 */
@Entity
@Table(name = "transaction_screening")
public class TransactionScreeningEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "txn_ref", nullable = false, length = 64)
    private String txnRef;

    @Column(name = "partner_id", length = 32)
    private String partnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "party", nullable = false, length = 16)
    private PaymentParty party;

    @Column(name = "subject_reference", length = 128)
    private String subjectReference;

    @Column(name = "subject_attributes", length = 256)
    private String subjectAttributes;

    @Column(name = "provider_id", nullable = false, length = 32)
    private String providerId;

    @Column(name = "provider_authoritative", nullable = false)
    private boolean providerAuthoritative;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ScreeningResult.Status status;

    @Column(name = "caveat", length = 512)
    private String caveat;

    @Enumerated(EnumType.STRING)
    @Column(name = "unscreened_reason", length = 32)
    private UnscreenedReason unscreenedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "posture", nullable = false, length = 48)
    private TransactionScreeningPolicy.Posture posture;

    @Column(name = "screened_at", nullable = false)
    private Instant screenedAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected TransactionScreeningEntity() {
        // JPA
    }

    /**
     * Build a persistable row from normalised evidence. Truncation to microseconds matches the
     * {@code TIMESTAMP} column's resolution so a value read back equals the value written.
     */
    public static TransactionScreeningEntity from(TransactionScreeningEvidence e, Instant recordedAt) {
        TransactionScreeningEntity row = new TransactionScreeningEntity();
        row.apply(e, recordedAt);
        return row;
    }

    /**
     * Overwrite this row from a fresh outcome for the same {@code (txn_ref, party)}. Used when a party
     * is re-screened: the current answer stays single-valued (one row per party, enforced by the unique
     * index) while the append-only history of how it changed lives in the hash-chained {@code
     * audit_log}, which this table is not and does not pretend to be.
     */
    public void apply(TransactionScreeningEvidence e, Instant recordedAt) {
        this.txnRef = e.txnRef();
        this.partnerId = e.partnerId();
        this.party = e.party();
        this.subjectReference = clamp(e.subjectReference(), 128);
        this.subjectAttributes = clamp(e.subjectAttributes(), 256);
        this.providerId = e.providerId();
        this.providerAuthoritative = e.providerAuthoritative();
        this.status = e.status();
        this.caveat = clamp(e.caveat(), 512);
        this.unscreenedReason = e.unscreenedReason();
        this.posture = e.posture();
        this.screenedAt = e.screenedAt().truncatedTo(ChronoUnit.MICROS);
        this.recordedAt = (recordedAt == null ? Instant.now() : recordedAt)
                .truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Reconstruct the evidence, re-applying every invariant of
     * {@link TransactionScreeningEvidence}. This is the ONLY sanctioned read path.
     */
    public TransactionScreeningEvidence toEvidence() {
        return new TransactionScreeningEvidence(
                txnRef, partnerId, party, subjectReference, subjectAttributes,
                providerId, providerAuthoritative, status, screenedAt, caveat, posture,
                unscreenedReason);
    }

    public Long getId() {
        return id;
    }

    public String getTxnRef() {
        return txnRef;
    }

    public PaymentParty getParty() {
        return party;
    }

    /** When the row was written (as opposed to when the provider answered). */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    private static String clamp(String v, int max) {
        if (v == null) {
            return null;
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
