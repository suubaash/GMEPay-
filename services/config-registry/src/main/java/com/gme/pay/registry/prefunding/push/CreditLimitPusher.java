package com.gme.pay.registry.prefunding.push;

import com.gme.pay.registry.commercial.LimitsEntity;
import com.gme.pay.registry.commercial.LimitsRepository;
import com.gme.pay.registry.persistence.PartnerEntity;
import com.gme.pay.registry.prefunding.PrefundingConfigEntity;
import com.gme.pay.registry.prefunding.PrefundingConfigRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Assembles the Wave-3 credit-limit push (IR-pf-2) from the two aggregates that
 * hold a partner's caps — {@code partner_prefunding_config} (V015,
 * {@code credit_limit_usd}) and {@code partner_limits} (V020/V034, the AML
 * daily/monthly/annual volume caps + daily transaction-count cap) — and hands
 * it to the gated {@link PrefundingCreditLimitClient}.
 *
 * <p>Called from {@code PrefundingConfigService} (when the credit line is set)
 * and {@code LimitsService} (when the AML caps are set) so the push fires on
 * EITHER write, always carrying the merged current state of BOTH aggregates —
 * prefunding gets one consistent picture regardless of which panel the operator
 * saved. Runs INSIDE the caller's transaction: the current rows it reads are
 * the just-saved ones, and a transport failure (REST mode) propagates and rolls
 * the caller's write back, the same hard-fail contract as the
 * notification-webhook provisioning push.
 *
 * <p>With the default {@link NoOpPrefundingCreditLimitClient} the call is a
 * cheap two-row read + a logged drop, so wiring it unconditionally into the
 * write paths costs nothing until prefunding is actually wired
 * ({@code gmepay.prefunding.client=rest}).
 */
@Component
public class CreditLimitPusher {

    private final PrefundingConfigRepository prefundingConfigRepository;
    private final LimitsRepository limitsRepository;
    private final PrefundingCreditLimitClient client;

    public CreditLimitPusher(PrefundingConfigRepository prefundingConfigRepository,
                             LimitsRepository limitsRepository,
                             PrefundingCreditLimitClient client) {
        this.prefundingConfigRepository = prefundingConfigRepository;
        this.limitsRepository = limitsRepository;
        this.client = client;
    }

    /**
     * Gather the partner's current credit line + AML caps and push them. A skip
     * when both aggregates are absent or every cap is null — there is nothing
     * for prefunding to gate on.
     */
    public void pushFor(PartnerEntity partner) {
        Optional<PrefundingConfigEntity> prefunding =
                prefundingConfigRepository.findCurrentByPartnerId(partner.getId());
        Optional<LimitsEntity> limits =
                limitsRepository.findCurrentByPartnerId(partner.getId());

        CreditLimitPushCommand command = new CreditLimitPushCommand(
                prefunding.map(PrefundingConfigEntity::getCreditLimitUsd).orElse(null),
                limits.map(LimitsEntity::getDailyCapUsd).orElse(null),
                limits.map(LimitsEntity::getMonthlyCapUsd).orElse(null),
                limits.map(LimitsEntity::getAnnualCapUsd).orElse(null),
                limits.map(LimitsEntity::getDailyTxnCountLimit).orElse(null));

        if (command.isEmpty()) {
            return;
        }
        client.pushCreditLimit(partner.getPartnerCode(), command);
    }
}
