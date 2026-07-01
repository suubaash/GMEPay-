package com.gme.pay.registry.prefunding.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link PrefundingCreditLimitClient}: logs and drops the push so local
 * dev and {@code @DataJpaTest} slices never need prefunding running. Active
 * unless {@code gmepay.prefunding.client=rest} promotes
 * {@link RestPrefundingCreditLimitClient} — the same stub-by-default discipline
 * as {@code StubKybClient} / {@code StubNotificationWebhookClient}.
 *
 * <p>A no-op (not a recording stub) because nothing in-process consumes the
 * caps: prefunding is the sole consumer, and when it is not wired the push is
 * genuinely a side-effect-free skip. The DEBUG line keeps it observable.
 */
@Component
@ConditionalOnProperty(name = "gmepay.prefunding.client", havingValue = "stub",
        matchIfMissing = true)
public class NoOpPrefundingCreditLimitClient implements PrefundingCreditLimitClient {

    private static final Logger log = LoggerFactory.getLogger(NoOpPrefundingCreditLimitClient.class);

    @Override
    public void pushCreditLimit(String partnerCode, CreditLimitPushCommand command) {
        log.debug("prefunding credit-limit push skipped (no-op client) for partner {}: {}",
                partnerCode, command);
    }
}
