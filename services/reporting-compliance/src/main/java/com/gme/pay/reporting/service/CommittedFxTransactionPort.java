package com.gme.pay.reporting.service;

import com.gme.pay.reporting.domain.CommittedTransaction;

import java.time.LocalDate;
import java.util.List;

/**
 * Port (secondary / outbound): the <b>rate-locked committed-transaction FX stream</b>.
 *
 * <p>This is deliberately separate from {@link TransactionClient}, which consumes the
 * canonical {@code GET /v1/transactions} endpoint. That endpoint does NOT expose the
 * rate-engine-locked margin fields, so {@link com.gme.pay.reporting.infrastructure.RestTransactionClient}
 * is forced to set {@code offerRateColl=null} and {@code partnerId=0} — which leaves
 * <b>BOK FX1015 field #14 unpopulated</b> (the documented gap).
 *
 * <p>This port models the contract this service actually needs from transaction-mgmt:
 * each {@link CommittedTransaction} carries {@code offerRateColl} (FX1015 #14),
 * {@code crossRate}, the intermediary USD amount, and {@code partnerId} — all locked at
 * CommitTransaction. See INTEGRATION REQUEST #1: transaction-mgmt must expose a
 * committed-FX projection (e.g. {@code GET /v1/transactions/fx-committed}) carrying
 * {@code offerRateColl} / {@code crossRate} / {@code partnerId}.
 *
 * <p>Until that endpoint exists, {@link com.gme.pay.reporting.infrastructure.FixtureCommittedFxTransactionPort}
 * supplies an in-process fixture so the FX1015 #14 path is exercised end-to-end and
 * stays green. Once the real endpoint lands, an HTTP adapter replaces the fixture with
 * no change to {@link BokReportService}.
 */
public interface CommittedFxTransactionPort {

    /**
     * Returns committed cross-border transactions whose KST report-date falls in
     * [{@code from}, {@code to}] (both inclusive), each carrying its rate-locked
     * BOK FX fields ({@code offerRateColl}, {@code crossRate}).
     *
     * @param from      inclusive start (KST date)
     * @param to        inclusive end (KST date)
     * @param partnerId filter by partner; null = all partners
     * @return committed transactions with FX fields populated; never null
     */
    List<CommittedTransaction> fetchCommittedFx(LocalDate from, LocalDate to, Long partnerId);
}
