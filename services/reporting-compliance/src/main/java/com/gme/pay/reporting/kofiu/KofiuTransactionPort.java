package com.gme.pay.reporting.kofiu;

import java.time.LocalDate;
import java.util.List;

/**
 * Port (secondary / outbound): fetches KoFIU-enriched committed transactions
 * for a given KST date window.
 *
 * <p>This port is owned by Lane C (KoFIU). It is distinct from
 * {@link com.gme.pay.reporting.service.TransactionClient} (Lane A) because it
 * carries the end-user identity ({@code endUserId}) that the base BOK client
 * intentionally omits.
 *
 * <p>Production implementation is out-of-scope (no real transaction-mgmt creds);
 * only a stub is wired by default.
 */
public interface KofiuTransactionPort {

    /**
     * Returns all committed cross-border transactions whose KST-date falls in
     * [{@code fromKst}, {@code toKst}] (both inclusive).
     *
     * @param fromKst inclusive start date in KST
     * @param toKst   inclusive end date in KST
     * @return list of KoFIU-enriched committed transactions; never null
     */
    List<KofiuTransaction> fetchForKofiu(LocalDate fromKst, LocalDate toKst);
}
