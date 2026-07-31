package com.gme.pay.settlement.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Anti-corruption port: fetches the revenue-ledger's recognised merchant-fee revenue (KRW) for one
 * business date, used by the daily tie-out report to prove the settlement engine's fee take and the
 * revenue ledger agree to the cent.
 *
 * <p>The real implementation calls revenue-ledger's REST API ({@code GET /v1/revenue}); the fixture
 * fallback reports "ledger unavailable". Never reads revenue-ledger's database directly (MSA rule).
 */
public interface LedgerRevenuePort {

    /**
     * Total fee revenue (KRW) the ledger recognised for the given business date, or {@code null}
     * when the figure is unavailable — client disabled, revenue-ledger unreachable, no numeric
     * partner mapping for the date, or the ledger reports the fee in a non-KRW currency. The
     * tie-out report stays useful without it ({@code ledgerAvailable=false}).
     */
    BigDecimal revenueFor(LocalDate date);
}
