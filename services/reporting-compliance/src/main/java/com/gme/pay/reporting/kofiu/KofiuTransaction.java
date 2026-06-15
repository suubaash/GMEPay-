package com.gme.pay.reporting.kofiu;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * KoFIU-enriched projection of a committed transaction.
 *
 * <p>Extends the base transaction data with {@code endUserId} (the end-customer
 * identity, NOT the partner) — required for per-end-user CTR daily aggregation
 * (KoFIU 특정금융거래정보 보고 규정, Article 4). A single end-user crossing the
 * CTR threshold in a single KST day triggers a report regardless of how many
 * partner accounts they transact through.
 *
 * <p>All money fields are BigDecimal-as-string on the wire; this object holds
 * them as BigDecimal internally.
 *
 * <p>Corridor is modelled as {@code srcCcy}/{@code dstCcy} so that the STR
 * per-corridor flag (V029_1) can be evaluated without a separate join.
 */
public final class KofiuTransaction {

    /** KST = UTC+9 — all KoFIU reporting dates are in Korea Standard Time. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final long txnId;
    private final String txnRef;

    /**
     * End-user (remitter/beneficiary) customer identifier.
     * This is NOT the partner id — CTR is aggregated per end-user.
     */
    private final String endUserId;

    /** Collection (send) amount in local currency. */
    private final BigDecimal collectionAmountKrw;

    /** ISO-4217 source currency code (e.g. KRW). */
    private final String srcCcy;

    /** ISO-4217 destination currency code (e.g. USD, PHP). */
    private final String dstCcy;

    /** Partner id — used for regulatory-config lookup. */
    private final long partnerId;

    /** When the transaction committed (UTC). */
    private final Instant committedAt;

    public KofiuTransaction(
            long txnId,
            String txnRef,
            String endUserId,
            BigDecimal collectionAmountKrw,
            String srcCcy,
            String dstCcy,
            long partnerId,
            Instant committedAt) {
        this.txnId = txnId;
        this.txnRef = txnRef;
        this.endUserId = endUserId;
        this.collectionAmountKrw = collectionAmountKrw;
        this.srcCcy = srcCcy;
        this.dstCcy = dstCcy;
        this.partnerId = partnerId;
        this.committedAt = committedAt;
    }

    /** KST date of this transaction (used for daily CTR window). */
    public LocalDate kstDate() {
        return LocalDate.ofInstant(committedAt, KST);
    }

    public long getTxnId() { return txnId; }
    public String getTxnRef() { return txnRef; }
    public String getEndUserId() { return endUserId; }
    public BigDecimal getCollectionAmountKrw() { return collectionAmountKrw; }
    public String getSrcCcy() { return srcCcy; }
    public String getDstCcy() { return dstCcy; }
    public long getPartnerId() { return partnerId; }
    public Instant getCommittedAt() { return committedAt; }
}
