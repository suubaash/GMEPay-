package com.gme.pay.prefunding;

import com.gme.pay.prefunding.audit.PrefundingAuditor;
import com.gme.pay.prefunding.audit.PrefundingAuditor.BalanceState;
import com.gme.pay.prefunding.audit.PrefundingAuditor.Movement;
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
    private final PrefundingAuditor audit;

    public PrefundingSeedRunner(PartnerBalanceRepository balances, PrefundingAuditor audit) {
        this.balances = balances;
        this.audit = audit;
    }

    @Override
    public void run(String... args) {
        if (balances.count() > 0) {
            return;
        }
        PartnerBalanceEntity saved = balances.save(new PartnerBalanceEntity(
                "SENDMN", "USD",
                new BigDecimal("50000.00000000"),
                new BigDecimal("10000.00000000"),
                Instant.now()));
        // Gap T5-1: USD 50,000 of float appearing on an empty table is exactly the kind of balance a
        // reviewer must be able to trace. It is audited under a NAMED system principal rather than
        // left unrecorded, so if a seeded balance ever turns up somewhere it should not, the audit
        // trail says which component put it there.
        audit.balanceMovement(saved.getPartnerId(), PrefundingAuditor.BALANCE_PROVISIONED,
                new BalanceState(null, null, null, null),
                BalanceState.of(saved),
                new Movement("PROVISION", saved.getBalance(), null, null,
                        "local demo seed (PrefundingSeedRunner; excluded from the 'test' profile "
                                + "and a no-op on any non-empty table)"),
                PrefundingAuditor.SYSTEM_DEMO_SEED_RUNNER);
    }
}
