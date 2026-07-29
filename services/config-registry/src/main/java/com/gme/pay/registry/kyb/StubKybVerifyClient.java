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
 * an AUTHORITATIVE {@code CLEAR} → {@code APPROVED}; anything else →
 * {@code MANUAL_REVIEW}. The adapter additionally weighs document completeness
 * and business-registration; the stub does not, so it never REJECTs — a real
 * verdict needs the rest transport.
 *
 * <h2>T1-4: this client can only ever return MANUAL_REVIEW</h2>
 *
 * <p>It runs {@link StubKybAdapter}, whose provenance is non-authoritative by
 * construction, so its screening can never be {@code CLEAR} — the clean branch is
 * {@code NOT_SCREENED_NO_PROVIDER}. {@code APPROVED} is therefore unreachable
 * here, and it should be: the previous behaviour returned {@code APPROVED} with
 * the reason "sanctions screening clear (stub)" for every partner whose name
 * lacked a trigger word, and config-registry stored that as the partner's
 * verification decision. The {@code APPROVED} branch is retained (rather than
 * deleted) so that pointing {@code gmepay.kyb-adapter.client=rest} at a real
 * provider needs no change here.
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
        // An approval requires a screening that actually happened — not merely the
        // absence of a match (T1-4).
        boolean approved = screening.screeningPerformed()
                && status == ScreeningResult.Status.CLEAR;
        String decision = approved ? "APPROVED" : "MANUAL_REVIEW";
        String reason;
        if (approved) {
            reason = "sanctions screening clear";
        } else if (!screening.screeningPerformed()) {
            reason = "no authoritative sanctions screening was performed ("
                    + screening.provenance().providerId() + "): " + screening.caveat();
        } else {
            reason = "sanctions screening " + status + " — analyst review required";
        }
        Instant screenedAt = screening.screenedAt() == null
                ? Instant.now().truncatedTo(ChronoUnit.MICROS)
                : screening.screenedAt();
        return new KybVerificationResult(
                screening.providerRef(),
                decision,
                reason,
                status == null ? null : status.name(),
                screenedAt,
                screening.provenance().providerId(),
                screening.authoritative(),
                screening.caveat());
    }
}
