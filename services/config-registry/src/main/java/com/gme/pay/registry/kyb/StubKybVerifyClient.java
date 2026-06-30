package com.gme.pay.registry.kyb;

import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.StubKybAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link KybVerifyClient}: runs lib-kyb's deterministic
 * {@link StubKybAdapter} in-process and collapses its screening verdict into a
 * verification decision, so {@code @DataJpaTest} slices and local dev never need
 * kyb-adapter running. Active unless {@code gmepay.kyb-adapter.client=rest}
 * promotes {@link RestKybVerifyClient} — the same stub-by-default discipline as
 * {@link StubKybClient} (the screen seam).
 *
 * <p>Decision rule (a faithful-enough stand-in for the adapter's collapse):
 * {@code CLEAR} → {@code APPROVED}, {@code NEEDS_REVIEW}/anything-with-hits →
 * {@code MANUAL_REVIEW}. The adapter additionally weighs document completeness
 * and business-registration; the stub does not, so it never REJECTs — a real
 * verdict needs the rest transport.
 */
@Component
@ConditionalOnProperty(name = "gmepay.kyb-adapter.client", havingValue = "stub",
        matchIfMissing = true)
public class StubKybVerifyClient implements KybVerifyClient {

    private final StubKybAdapter adapter = new StubKybAdapter();

    @Override
    public KybVerificationResult verify(KybVerificationRequest request) {
        ScreeningResult screening = adapter.screen(request.subject());
        ScreeningResult.Status status = screening.status();
        boolean approved = status == ScreeningResult.Status.CLEAR;
        String decision = approved ? "APPROVED" : "MANUAL_REVIEW";
        String reason = approved
                ? "sanctions screening clear (stub)"
                : "sanctions screening " + status + " — analyst review required (stub)";
        Instant screenedAt = screening.screenedAt() == null
                ? Instant.now().truncatedTo(ChronoUnit.MICROS)
                : screening.screenedAt();
        return new KybVerificationResult(
                screening.providerRef(),
                decision,
                reason,
                status == null ? null : status.name(),
                screenedAt);
    }
}
