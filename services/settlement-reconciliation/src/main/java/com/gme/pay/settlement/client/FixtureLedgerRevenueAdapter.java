package com.gme.pay.settlement.client;

import com.gme.pay.settlement.port.LedgerRevenuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * In-process fallback for {@link LedgerRevenuePort}, active whenever the gated
 * {@link RestLedgerRevenueClient} is NOT enabled ({@code gmepay.clients.revenue-ledger.enabled}
 * unset/false) — i.e. dev/test where revenue-ledger is not running.
 *
 * <p>Returns {@code null} ("ledger figure unavailable"), which the tie-out report surfaces as
 * {@code ledgerAvailable=false} with {@code ledgerDeltaKrw} omitted — an HONEST absence rather than
 * a synthetic zero that would fake a perfect ledger match. The settlement-internal balance check
 * still runs, so the report stays useful without a live ledger.
 */
@Component
@ConditionalOnMissingBean(RestLedgerRevenueClient.class)
public class FixtureLedgerRevenueAdapter implements LedgerRevenuePort {

    private static final Logger log = LoggerFactory.getLogger(FixtureLedgerRevenueAdapter.class);

    @Override
    public BigDecimal revenueFor(LocalDate date) {
        log.info("[fixture] ledger revenue for {} unavailable (revenue-ledger client disabled)", date);
        return null;
    }
}
