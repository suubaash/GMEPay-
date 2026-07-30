package com.gme.pay.kyb;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The default {@link PaymentScreeningPort}: <b>there is no screening provider</b> — gap <b>T5-3</b>.
 *
 * <p>This class is not a stub and not a placeholder to be improved. It is the truthful implementation
 * of the platform's current state, and it is written so that state cannot be mistaken for a control:
 *
 * <ul>
 *   <li>it returns {@link ScreeningResult.Status#NOT_SCREENED_NO_PROVIDER}, never {@code CLEAR};</li>
 *   <li>it stamps {@link ScreeningProvenance#noProvider(String)}, so {@link #authoritative()} is
 *       {@code false} and the {@link ScreeningResult} constructor would coerce a {@code CLEAR} away
 *       even if someone changed the status line above;</li>
 *   <li>it carries a caveat that states in one sentence what did not happen.</li>
 * </ul>
 *
 * <p><b>Why not keyword-match like {@link StubKybAdapter} does?</b> Because that is what produced gap
 * T1-4. A substring match against no list is not a weaker screening, it is a different activity with a
 * screening's vocabulary, and the moment it can emit a clean-looking verdict some caller three hops
 * away treats it as one. There is nothing to demo here that is worth that risk: the interesting path to
 * exercise is the unscreened path, and this class produces it deterministically.
 *
 * <p>Replacing this bean is the entire integration: define a {@link PaymentScreeningPort} bean that
 * calls the vendor and returns {@link ScreeningProvenance#vendor(String)}-stamped results, and the
 * {@code @ConditionalOnMissingBean} default in payment-executor's {@code PaymentScreeningConfig} steps
 * aside. No gate, counter, alert, error code or query surface changes.
 */
public final class NoProviderPaymentScreeningPort implements PaymentScreeningPort {

    /** The caveat that travels with every result this port produces. */
    public static final String CAVEAT =
            "NOT SCREENED: no sanctions/PEP screening provider is configured on the payment path"
            + " (gap T5-3). No sanctions list, PEP register or adverse-media source was consulted for"
            + " this party. This is the platform's default state and is NOT a clean result.";

    private final Clock clock;

    public NoProviderPaymentScreeningPort() {
        this(Clock.systemUTC());
    }

    /** Test constructor — an explicit clock so {@code screenedAt} is reproducible. */
    public NoProviderPaymentScreeningPort(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ScreeningResult screen(PaymentScreeningSubject subject) {
        return new ScreeningResult(
                ScreeningResult.Status.NOT_SCREENED_NO_PROVIDER,
                List.of(),
                Instant.now(clock),
                null,
                ScreeningProvenance.noProvider(CAVEAT));
    }

    @Override
    public String providerId() {
        return ScreeningProvenance.NO_PROVIDER_ID;
    }

    /** Always {@code false} — and {@link ScreeningProvenance} refuses to let this id claim otherwise. */
    @Override
    public boolean authoritative() {
        return false;
    }
}
