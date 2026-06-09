package com.gme.pay.prefunding;

import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds a single demo OVERSEAS partner ("SENDMN") with a starting balance if (and only if) the
 * partner_balance table is empty. This preserves the in-memory store's seed behaviour for local
 * runs and integration smoke tests; production deployments come up with an empty table.
 * The 'test' profile is excluded so integration tests start from a known empty state.
 */
@Component
@Profile("!test")
public class PrefundingSeedRunner implements CommandLineRunner {

    private final PartnerBalanceRepository balances;

    public PrefundingSeedRunner(PartnerBalanceRepository balances) {
        this.balances = balances;
    }

    @Override
    public void run(String... args) {
        if (balances.count() > 0) {
            return;
        }
        balances.save(new PartnerBalanceEntity(
                "SENDMN", "USD",
                new BigDecimal("50000.00000000"),
                new BigDecimal("10000.00000000"),
                Instant.now()));
    }
}
