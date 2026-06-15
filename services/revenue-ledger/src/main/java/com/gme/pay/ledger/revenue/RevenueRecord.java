package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single revenue record capturing FX margin and service-charge income for one committed transaction.
 *
 * <p>This is an insert-only value — monetary fields are immutable after creation, matching the
 * rate-lock immutability rule (RATE-04 §9.4).
 *
 * <p>Keyed by {@code txnRef} — the transaction-mgmt business reference (e.g. {@code "TXN-..."}) that
 * the payment path carries end-to-end. (Earlier this was a numeric {@code txnId}, but the orchestrated
 * payment path never has the numeric surrogate — only the string reference — so the natural,
 * universally-available key is {@code txnRef}.)
 *
 * <p>For same-currency (domestic) transactions, {@code fxMarginUsd} is exactly {@code 0.0000}.
 * Service charge is always recorded even when margins are zero.
 *
 * <p>Formula: {@code fxMarginUsd = collectionMarginUsd + payoutMarginUsd}.
 */
public final class RevenueRecord {

    private final String txnRef;
    private final long partnerId;
    private final long schemeId;
    private final LocalDate revenueDate;
    private final BigDecimal fxMarginUsd;
    private final BigDecimal serviceChargeAmount;
    private final String serviceChargeCcy;
    private final BigDecimal feeSharePct;

    private RevenueRecord(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                          BigDecimal fxMarginUsd, BigDecimal serviceChargeAmount,
                          String serviceChargeCcy, BigDecimal feeSharePct) {
        Objects.requireNonNull(txnRef, "txnRef required");
        if (txnRef.isBlank()) {
            throw new IllegalArgumentException("txnRef must not be blank");
        }
        Objects.requireNonNull(revenueDate, "revenueDate required");
        Objects.requireNonNull(fxMarginUsd, "fxMarginUsd required");
        Objects.requireNonNull(serviceChargeAmount, "serviceChargeAmount required");
        Objects.requireNonNull(serviceChargeCcy, "serviceChargeCcy required");
        Objects.requireNonNull(feeSharePct, "feeSharePct required");
        if (fxMarginUsd.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("fxMarginUsd must be >= 0");
        }
        if (serviceChargeAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("serviceChargeAmount must be >= 0");
        }
        this.txnRef = txnRef;
        this.partnerId = partnerId;
        this.schemeId = schemeId;
        this.revenueDate = revenueDate;
        this.fxMarginUsd = fxMarginUsd;
        this.serviceChargeAmount = serviceChargeAmount;
        this.serviceChargeCcy = serviceChargeCcy;
        this.feeSharePct = feeSharePct;
    }

    /** Create a revenue record. fxMarginUsd = collectionMarginUsd + payoutMarginUsd. */
    public static RevenueRecord of(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                   BigDecimal collectionMarginUsd, BigDecimal payoutMarginUsd,
                                   BigDecimal serviceChargeAmount, String serviceChargeCcy,
                                   BigDecimal feeSharePct) {
        Objects.requireNonNull(collectionMarginUsd, "collectionMarginUsd required");
        Objects.requireNonNull(payoutMarginUsd, "payoutMarginUsd required");
        BigDecimal fxMarginUsd = collectionMarginUsd.add(payoutMarginUsd);
        return new RevenueRecord(txnRef, partnerId, schemeId, revenueDate,
                fxMarginUsd, serviceChargeAmount, serviceChargeCcy, feeSharePct);
    }

    /** Create a revenue record for a same-currency (domestic) transaction where fxMarginUsd = 0. */
    public static RevenueRecord sameCurrency(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                             BigDecimal serviceChargeAmount, String serviceChargeCcy,
                                             BigDecimal feeSharePct) {
        return new RevenueRecord(txnRef, partnerId, schemeId, revenueDate,
                BigDecimal.ZERO, serviceChargeAmount, serviceChargeCcy, feeSharePct);
    }

    /**
     * Rebuild a record from already-persisted fields, where {@code fxMarginUsd} is the stored total
     * (not the two component margins). Used by the JPA store on read-back so the persisted total is
     * preserved exactly. Other callers should prefer {@link #of} / {@link #sameCurrency}.
     */
    public static RevenueRecord rehydrate(String txnRef, long partnerId, long schemeId, LocalDate revenueDate,
                                          BigDecimal fxMarginUsd, BigDecimal serviceChargeAmount,
                                          String serviceChargeCcy, BigDecimal feeSharePct) {
        return new RevenueRecord(txnRef, partnerId, schemeId, revenueDate,
                fxMarginUsd, serviceChargeAmount, serviceChargeCcy, feeSharePct);
    }

    public String txnRef() { return txnRef; }
    public long partnerId() { return partnerId; }
    public long schemeId() { return schemeId; }
    public LocalDate revenueDate() { return revenueDate; }
    public BigDecimal fxMarginUsd() { return fxMarginUsd; }
    public BigDecimal serviceChargeAmount() { return serviceChargeAmount; }
    public String serviceChargeCcy() { return serviceChargeCcy; }
    public BigDecimal feeSharePct() { return feeSharePct; }
}
