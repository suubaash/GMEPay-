package com.gme.pay.payment.web;

import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.payment.domain.PaymentScreeningGate;
import com.gme.pay.payment.persistence.UnscreenedPaymentCounter;
import com.gme.pay.payment.persistence.UnscreenedPaymentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The answer to "how many payments went through unscreened?" — gap <b>T5-3</b>.
 *
 * <p><b>{@code GET /internal/ops/screening-coverage}</b>. Before this existed the question had no
 * answer at all: not "zero", not "all of them" — no data, because nothing on the payment path recorded
 * anything about screening. That is the state a regulator's first question lands in.
 *
 * <h2>The counts are never returned alone</h2>
 * <p>Every response leads with the <b>posture</b> — which provider is configured, whether it is
 * authoritative, and whether fail-closed is armed — and only then the counts. This is not decoration.
 * An empty count set is ambiguous in the most dangerous possible way: it means either "every party was
 * screened clean" or "nothing is wired / no traffic", and a dashboard that rendered a bare zero would
 * turn the second into the first. With {@code screeningActive=false} on the same object, a zero can only
 * be read one way.
 *
 * <h2>Authorisation</h2>
 * <p>Mounted under {@code /internal/**}, which {@code SandboxSurfaceInternalAuthConfig} gates
 * <b>unconditionally</b> with the platform internal token ({@code X-Gme-Internal}) — including when no
 * secret is configured, in which case every caller is refused 401. Fail-closed, and required here: the
 * rows name partner codes and their unscreened volumes, which is precisely the material an attacker
 * would use to pick a corridor.
 *
 * <p>No counterparty PII is reachable through this surface because none is stored (see the V010 header).
 */
@RestController
@RequestMapping("/internal/ops/screening-coverage")
@Tag(name = "Sanctions screening coverage (internal)",
        description = "How many payments were accepted without a sanctions/PEP screening, and why"
                + " (gap T5-3). Counts are meaningless without the posture fields returned alongside"
                + " them — see screeningActive.")
public class ScreeningCoverageController {

    private final UnscreenedPaymentCounter counter;
    private final PaymentScreeningGate gate;

    public ScreeningCoverageController(UnscreenedPaymentCounter counter, PaymentScreeningGate gate) {
        this.counter = counter;
        this.gate = gate;
    }

    /**
     * Screening posture plus the unscreened-payment counts.
     *
     * @param from       optional inclusive start date (ISO {@code yyyy-MM-dd}); omitted = all time
     * @param reasonCode optional exact {@link UnscreenedReason} filter
     * @param limit      page size; defaults to 50, hard-capped at {@link UnscreenedPaymentCounter#MAX_LIMIT}
     */
    @GetMapping
    @Operation(summary = "Unscreened-payment counts + the screening posture that gives them meaning")
    public ScreeningCoverageResponse coverage(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(defaultValue = "50") int limit) {

        List<UnscreenedPaymentEntity> rows = counter.recent(from, reasonCode, limit);
        long total = counter.total(from);
        boolean active = gate.screeningActive();
        return new ScreeningCoverageResponse(
                active,
                gate.providerId(),
                gate.failClosed(),
                interpretation(active, total),
                from,
                total,
                rows.stream().map(UnscreenedAggregate::from).toList());
    }

    /**
     * The sentence that stops a zero being misread. Returned as data rather than left to each consumer
     * to compose, because the T5-2 lesson was that every consumer composes it differently and at least
     * one composes it wrongly.
     */
    private static String interpretation(boolean active, long total) {
        if (!active) {
            return "NO SCREENING IS CONFIGURED on the payment path (gap T5-3). Every payment this"
                    + " service accepts is unscreened. A total of 0 here means NOTHING WAS COUNTED"
                    + " (no traffic, or the counter could not write) — it does NOT mean payments were"
                    + " screened.";
        }
        return total == 0
                ? "An authoritative screening provider is configured and no unscreened party has been"
                  + " counted in this window."
                : "An authoritative screening provider is configured, but " + total + " parties still"
                  + " went unscreened in this window — see the per-reason rows for the cause and owner.";
    }

    /**
     * @param screeningActive {@code true} only when an authoritative provider is wired. <b>Read this
     *                        before the numbers.</b>
     * @param providerId      the configured provider ({@code "none"} when nothing is wired)
     * @param failClosed      whether unscreened payments are being REFUSED
     *                        ({@code gmepay.screening.fail-closed})
     * @param interpretation  how to read {@code totalUnscreenedParties} given the posture above
     * @param from            the window start echoed back ({@code null} = all time)
     * @param totalUnscreenedParties sum of the counts below; a LOWER BOUND (the counter never throws,
     *                        so a failed write is a missing payment — see {@code UnscreenedPaymentCounter})
     * @param byCause         the aggregate rows
     */
    public record ScreeningCoverageResponse(
            boolean screeningActive,
            String providerId,
            boolean failClosed,
            String interpretation,
            LocalDate from,
            long totalUnscreenedParties,
            List<UnscreenedAggregate> byCause) {
    }

    /** One {@code unscreened_payments} row (V010). */
    public record UnscreenedAggregate(
            LocalDate gapDate,
            String reasonCode,
            String reasonDescription,
            String partyRole,
            String providerId,
            String partnerRef,
            long paymentCount,
            Instant firstSeenAt,
            Instant lastSeenAt,
            String firstPaymentRef,
            String lastPaymentRef) {

        static UnscreenedAggregate from(UnscreenedPaymentEntity e) {
            return new UnscreenedAggregate(
                    e.getGapDate(),
                    e.getReasonCode(),
                    describe(e.getReasonCode()),
                    e.getPartyRole(),
                    e.getProviderId(),
                    e.getPartnerRef(),
                    e.getPaymentCount(),
                    e.getFirstSeenAt(),
                    e.getLastSeenAt(),
                    e.getFirstPaymentRef(),
                    e.getLastPaymentRef());
        }

        /** Expand the stored code so a consumer need not carry the roster. Unknown codes pass through. */
        private static String describe(String reasonCode) {
            try {
                return UnscreenedReason.valueOf(reasonCode).description();
            } catch (IllegalArgumentException | NullPointerException unknown) {
                return "unrecognised reason code '" + reasonCode + "'";
            }
        }
    }
}
