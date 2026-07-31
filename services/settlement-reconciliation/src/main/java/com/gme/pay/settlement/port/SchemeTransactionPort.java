package com.gme.pay.settlement.port;

import com.gme.pay.settlement.corridor.SchemeTransactionRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * Anti-corruption port for leg (a) of the cross-border three-way tie-out: APPROVED transactions of
 * ONE scheme for one settlement date, with the FX/USD fields the KRW-settlement projection drops.
 *
 * <p>Distinct from {@link TransactionQueryPort} on purpose: that port answers "what has not been
 * batched into a ZeroPay settlement file yet" and projects KRW payout only. This one answers "what
 * did this scheme's corridor do on date D, and what USD did we move for it".
 *
 * <p>MSA rule unchanged: settlement-reconciliation NEVER reads transaction-mgmt's database.
 */
public interface SchemeTransactionPort {

    /**
     * All APPROVED transactions of {@code schemeId} whose scheme approval fell on
     * {@code settlementDate}.
     *
     * @param schemeId       scheme identifier as transaction-mgmt records it (e.g. {@code sendmn})
     * @param settlementDate business date being reconciled
     * @return matching records; empty (never null) when the day had none or the source is unreachable
     */
    List<SchemeTransactionRecord> findApprovedByScheme(String schemeId, LocalDate settlementDate);
}
