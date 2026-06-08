package com.gme.pay.ledger.revenue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port: persistence for {@link RevenueRecord}.
 * Implementations must guarantee idempotency: saving a record with a duplicate txnId is a no-op.
 *
 * <p>This interface is owned by revenue-ledger. Other services must NOT implement or call it.
 */
public interface RevenueRecordStore {

    /** Persist a revenue record. Returns the stored record (possibly unchanged if already exists). */
    RevenueRecord save(RevenueRecord record);

    /** Find by transaction id (at most one row per committed transaction). */
    Optional<RevenueRecord> findByTxnId(long txnId);

    /** Sum FX margin USD for a partner over a date range (COALESCE 0 on no rows). */
    BigDecimal sumFxMarginUsdByPartnerAndDateRange(long partnerId, LocalDate start, LocalDate end);

    /** Sum service-charge amount for a scheme over a date range (COALESCE 0 on no rows). */
    BigDecimal sumServiceChargeBySchemeAndDateRange(long schemeId, LocalDate start, LocalDate end);

    /** Page of revenue records for the given date range (for CSV export). */
    List<RevenueRecord> findByDateRangePaged(LocalDate start, LocalDate end, int page, int size);
}
