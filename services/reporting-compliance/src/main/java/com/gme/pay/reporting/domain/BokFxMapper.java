package com.gme.pay.reporting.domain;

import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Pure-domain mapper: converts a {@link CommittedTransaction} to a {@link BokFxRecord}.
 *
 * <p>Mapping rules (DAT-03 §8.1, SEC-09 §8.1.3):
 * <ul>
 *   <li>Domestic / same-currency transactions ({@code sameCcyShortcircuit=true}) are
 *       EXEMPT from BOK FX reporting. Calling {@link #toRecord} with such a transaction
 *       throws {@link IllegalArgumentException}.</li>
 *   <li>INBOUND  → {@link BokReportType#FX1015}</li>
 *   <li>OUTBOUND → {@link BokReportType#FX1014}</li>
 *   <li>HUB      → {@link BokReportType#FX1015} (provisional, pending OI-03 confirmation)</li>
 *   <li>DOMESTIC → exempt; {@link #toRecord} throws.</li>
 * </ul>
 *
 * <p>{@code offerRateColl} (BOK FX1015 field #14) is taken directly from the locked
 * transaction value — it is NOT recomputed here.  The formula, applied by the rate engine
 * at commit time, is:
 * <pre>
 *   offerRateColl = send_amount / (collection_usd - collection_margin_usd)
 * </pre>
 *
 * <p>This class is stateless and has no Spring dependencies; it can be instantiated
 * directly in unit tests.
 */
public final class BokFxMapper {

    /** KST = UTC+9. Used to derive report_date from committed_at. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** Initial submission status for every new BOK record. */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * Maps a committed cross-border transaction to its BOK FX record.
     *
     * @param txn the committed transaction; must not be null and must not be
     *            a same-currency short-circuit transaction.
     * @return a new {@link BokFxRecord} with {@code submissionStatus=PENDING}.
     * @throws IllegalArgumentException if {@code txn.isSameCcyShortcircuit()} is true
     *         or if the direction is {@link TransactionDirection#DOMESTIC}.
     * @throws NullPointerException if {@code txn} is null.
     */
    public BokFxRecord toRecord(CommittedTransaction txn) {
        Objects.requireNonNull(txn, "txn must not be null");

        if (txn.isSameCcyShortcircuit()
                || txn.getDirection() == TransactionDirection.DOMESTIC) {
            throw new IllegalArgumentException(
                    "Domestic/same-currency transactions are exempt from BOK FX reporting "
                    + "(txnId=" + txn.getTxnId() + ", direction=" + txn.getDirection()
                    + ", sameCcyShortcircuit=" + txn.isSameCcyShortcircuit() + ")");
        }

        BokReportType reportType = resolveReportType(txn.getDirection());
        LocalDate reportDate = LocalDate.ofInstant(txn.getCommittedAt(), KST);

        return new BokFxRecord(
                txn.getTxnId(),
                txn.getTxnRef(),
                reportType,
                reportDate,
                txn.getPartnerId(),
                txn.getCollectionAmount(),
                txn.getCollectionCcy(),
                txn.getPayoutAmount(),
                txn.getPayoutCcy(),
                txn.getOfferRateColl(),   // BOK FX1015 field #14 — locked at commit
                txn.getCrossRate(),
                txn.getUsdAmount(),
                STATUS_PENDING);
    }

    /**
     * Resolves the BOK report form type from the transaction direction.
     *
     * @param direction INBOUND|OUTBOUND|HUB (DOMESTIC must not be passed here)
     * @return FX1015 for INBOUND/HUB; FX1014 for OUTBOUND.
     */
    public static BokReportType resolveReportType(TransactionDirection direction) {
        return switch (direction) {
            case INBOUND -> BokReportType.FX1015;
            case OUTBOUND -> BokReportType.FX1014;
            case HUB -> {
                // OI-03 PENDING – BOK format for HUB multi-leg not yet confirmed.
                // Defaulting to FX1015 (inbound funds leg) until OI-03 is resolved.
                yield BokReportType.FX1015;
            }
            case DOMESTIC -> throw new IllegalArgumentException(
                    "DOMESTIC direction has no BOK report type — caller must screen before invoking resolveReportType");
        };
    }
}
