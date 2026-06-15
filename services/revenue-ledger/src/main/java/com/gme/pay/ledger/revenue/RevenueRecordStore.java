package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port: persistence for {@link RevenueRecord}.
 * Implementations must guarantee idempotency: saving a record with a duplicate txnRef is a no-op.
 *
 * <p>This interface is owned by revenue-ledger. Other services must NOT implement or call it.
 */
public interface RevenueRecordStore {

    /** Persist a revenue record. Returns the stored record (possibly unchanged if already exists). */
    RevenueRecord save(RevenueRecord record);

    /** Find by transaction reference (at most one row per committed transaction). */
    Optional<RevenueRecord> findByTxnRef(String txnRef);

    /** Sum FX margin USD for a partner over a date range (COALESCE 0 on no rows). */
    BigDecimal sumFxMarginUsdByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end);

    /** Sum service-charge amount for a partner over a date range (COALESCE 0 on no rows). */
    BigDecimal sumServiceChargeByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end);

    /** Count committed-transaction revenue rows for a partner over a date range. */
    long countByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end);

    /**
     * A representative service-charge currency for a partner over a date range — the ISO code most
     * of the partner's rows carry (a partner normally bills service charge in one currency). Returns
     * {@code null} when the partner has no rows in range; callers default it.
     */
    String serviceChargeCcyByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end);

    /** Sum service-charge amount for a scheme over a date range (COALESCE 0 on no rows). */
    BigDecimal sumServiceChargeBySchemeAndDateRange(long schemeId, LocalDate start, LocalDate end);

    /** Page of revenue records for the given date range (for CSV export). */
    List<RevenueRecord> findByDateRangePaged(LocalDate start, LocalDate end, int page, int size);
}
