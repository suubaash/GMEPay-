package com.gme.pay.payment.domain;

import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.PaymentScreeningPort;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.kyb.ScreeningResult;
import com.gme.pay.kyb.UnscreenedReason;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.persistence.UnscreenedPaymentCounter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sanctions/PEP screening seam on the NEW-payment path — gap <b>T5-3</b>.
 *
 * <p>The CISO audit's finding was that <b>nothing</b> screens a transaction, a sender or a beneficiary:
 * a grep of {@code payment-executor}, {@code smart-router} and {@code transaction-mgmt} for
 * sanctions/screening/watchlist/PEP returns no consumer. That was verified before this class was
 * written, and it was accurate. The only screening anywhere is the ONBOARDING-time partner KYB call
 * (config-registry → kyb-adapter), which screens the licensed institution and its UBOs — not the human
 * on either end of a payment. The "AML gates" already in {@link PaymentOrchestrator} are four numeric
 * comparisons against operator-entered caps (per-txn USD, daily/monthly/annual USD, daily txn count);
 * they are regulatory transaction limits, and they screen nobody.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 * <p>It is <b>a seam plus an honest accounting of its own emptiness</b>. It defines no rules, no
 * thresholds, no risk scores, no list roster and no PEP source, because those are policy and vendor
 * decisions owned by compliance — and an invented rule set would be worse than none: it would read, to
 * a reviewer and to a regulator, as a control that exists. What it does instead is make the ABSENCE of
 * screening a first-class, measured, alerted, queryable fact, and give a real provider one bean to
 * replace.
 *
 * <p>This is the {@link SchemeOperatingHoursGate} pattern (T3-6) applied to a harder subject, and it
 * reuses the T1-4 KYB vocabulary rather than inventing a parallel one: {@link ScreeningResult},
 * {@link com.gme.pay.kyb.ScreeningProvenance} and {@code NOT_SCREENED_NO_PROVIDER} all come from
 * lib-kyb, so a result that no authoritative provider produced <b>cannot structurally report
 * CLEAR</b> — the record's own constructor coerces it. That guarantee is inherited here for free,
 * which is the entire reason for the reuse.
 *
 * <h2>Policy table</h2>
 * <table>
 *   <caption>What each outcome does</caption>
 *   <tr><th>Outcome</th><th>Action</th><th>Why</th></tr>
 *   <tr><td>authoritative {@code CLEAR}</td><td>proceed silently</td>
 *       <td>a real provider consulted real lists and found nothing</td></tr>
 *   <tr><td>{@code HIT} / {@code NEEDS_REVIEW} from an authoritative provider</td>
 *       <td><b>refuse</b> ({@link PaymentScreeningRefusedException#SANCTIONS_HIT})</td>
 *       <td>honouring a provider's own adverse verdict is not a policy choice. Enforced even when
 *           fail-closed is OFF — the flag governs what happens when we know NOTHING, not what happens
 *           when we know something bad.</td></tr>
 *   <tr><td>not screened (any {@link UnscreenedReason})</td>
 *       <td><b>proceed</b> + WARN + metric + counted + de-duped ops alert;
 *           refuse only if {@code fail-closed=true}</td>
 *       <td>see below</td></tr>
 * </table>
 *
 * <h2>Why the default is fail-OPEN — and why that is the OPPOSITE of the KYB activation gate</h2>
 * <p>Gap T1-4 made partner activation <b>refuse</b> when no screening backs it
 * ({@code SANCTIONS_NOT_SCREENED}, non-overridable). This gate defaults to permitting. The two are not
 * inconsistent, and the difference is the shape of the decision, not a softer attitude to compliance:
 *
 * <ul>
 *   <li><b>Activation is a one-off, human-paced gate with a safe refusal.</b> An operator is sitting
 *       in a wizard; refusing costs a delay on one partner's onboarding and the correct answer is
 *       genuinely "then do not go live". Nothing is in flight and nobody is mid-payment.</li>
 *   <li><b>A live payment path is not.</b> Refusing every payment because no AML vendor is configured
 *       would take the LIVE corridors down — ZEROPAY, NEPAL and SENDMN — instantly and completely, for
 *       a control that has never existed in any environment. Whether to stop taking money is a business
 *       decision with customer, partner and licence consequences; it is the owner's call and
 *       compliance's call, not a side effect of shipping a seam.</li>
 * </ul>
 *
 * <p>So the switch exists and the platform will not touch it: {@code gmepay.screening.fail-closed},
 * <b>false by default</b>, absent from every deployed config, and bannered loudly at startup when it is
 * turned on. Turning it on means <b>every payment is refused</b> with
 * {@code SANCTIONS_SCREENING_UNAVAILABLE} until a real provider is wired AND the payment contracts
 * carry a screenable identity (see {@link UnscreenedReason#NO_SUBJECT_IDENTITY}) — it is a kill switch,
 * not a hardening step, and it should be flipped only in a corridor that is deliberately being stopped.
 *
 * <p>The corollary — the reason permitting is not the same as pretending — is the rest of this class:
 * a per-payment WARN, a per-payment Micrometer counter, a durable per-cause count in
 * {@code unscreened_payments} (V010), a de-duplicated ops alert, and a read surface that reports the
 * configured provider alongside the counts so an empty table can never be misread as coverage.
 *
 * <h2>Alert de-duplication</h2>
 * <p>Today EVERY payment is unscreened, so a per-payment alert would emit one alert per payment
 * forever: an unreadable stream, a filled {@code ops_alerts} table, and a notification sink nobody
 * trusts. The alert is therefore de-duplicated to one per {@code (reason, party, UTC date)} — the same
 * choice T3-6 made for {@code SCHEME_HOURS_UNVERIFIED} and T3-4 for {@code BATCH_CALENDAR_UNVERIFIED}.
 * The per-payment signal is not lost: it is the WARN line and the Micrometer counter, which are built
 * for volume; the alert is built for a human.
 */
@Component
public class PaymentScreeningGate {

    private static final Logger log = LoggerFactory.getLogger(PaymentScreeningGate.class);

    /** Ops-alert type raised when a payment is accepted without its parties being screened. */
    public static final String ALERT_UNSCREENED = "PAYMENT_SCREENING_UNAVAILABLE";

    /** Ops-alert type raised when an authoritative provider returns an adverse verdict. */
    public static final String ALERT_SANCTIONS_HIT = "PAYMENT_SANCTIONS_HIT";

    /** Micrometer counter incremented once per unscreened party per payment (no de-duplication). */
    public static final String METRIC_UNSCREENED = "gmepay.payments.screening.unscreened";

    /**
     * Startup banner when no authoritative provider is wired — the default state. ERROR level, matching
     * {@code FixtureSchemeOperatingHoursClient}: a missing control may be tolerated, it may not be
     * silent.
     */
    static final String NO_PROVIDER_BANNER =
            "NO TRANSACTION SANCTIONS/PEP SCREENING: no authoritative PaymentScreeningPort bean is"
                    + " configured, so NO payment's payer, beneficiary or merchant is screened against"
                    + " any sanctions list, PEP register or adverse-media source (gap T5-3). Payments"
                    + " are being ACCEPTED unscreened and counted in unscreened_payments (V010) —"
                    + " see GET /internal/ops/screening-coverage. Define a PaymentScreeningPort bean to"
                    + " close this; set gmepay.screening.fail-closed=true to refuse payments instead"
                    + " (this STOPS every live corridor — read PaymentScreeningGate's javadoc first).";

    /** Startup banner when the fail-closed kill switch is armed. */
    static final String FAIL_CLOSED_BANNER =
            "SCREENING FAIL-CLOSED IS ARMED: 'gmepay.screening.fail-closed=true'. Every new payment"
                    + " whose parties cannot be screened will be REFUSED with"
                    + " SANCTIONS_SCREENING_UNAVAILABLE. With no provider configured that is EVERY"
                    + " payment on every corridor. This is a deliberate stop, not a hardening step.";

    private final PaymentScreeningPort port;
    @Nullable private final OpsAlertPipeline alerts;
    @Nullable private final UnscreenedPaymentCounter counter;
    @Nullable private final MeterRegistry meters;
    private final boolean failClosed;
    private final Clock clock;

    /** (reason, party) → last UTC date an alert was raised for it (alert de-duplication). */
    private final Map<String, LocalDate> alerted = new ConcurrentHashMap<>();

    @Autowired
    public PaymentScreeningGate(PaymentScreeningPort port,
                                @Nullable OpsAlertPipeline alerts,
                                @Nullable UnscreenedPaymentCounter counter,
                                @Nullable MeterRegistry meters,
                                @Value("${gmepay.screening.fail-closed:false}") boolean failClosed) {
        this(port, alerts, counter, meters, failClosed, Clock.systemUTC());
    }

    /** Test constructor — explicit clock so the day-boundary de-duplication is reproducible. */
    public PaymentScreeningGate(PaymentScreeningPort port,
                               @Nullable OpsAlertPipeline alerts,
                               @Nullable UnscreenedPaymentCounter counter,
                               @Nullable MeterRegistry meters,
                               boolean failClosed,
                               Clock clock) {
        if (port == null) {
            throw new IllegalArgumentException(
                    "a PaymentScreeningPort is required — the honest 'no provider' state is a PORT"
                            + " (NoProviderPaymentScreeningPort), never a null gate, so that it is"
                            + " counted and alerted rather than silently skipped");
        }
        this.port = port;
        this.alerts = alerts;
        this.counter = counter;
        this.meters = meters;
        this.failClosed = failClosed;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @PostConstruct
    void bannerAtStartup() {
        if (!port.authoritative()) {
            log.error("!!! {}", NO_PROVIDER_BANNER);
        } else {
            log.info("transaction sanctions/PEP screening ACTIVE via provider '{}' (gap T5-3)",
                    port.providerId());
        }
        if (failClosed) {
            log.error("!!! {}", FAIL_CLOSED_BANNER);
        }
    }

    /**
     * Screen the parties of a NEW payment.
     *
     * <p>Runs before any side effect: no float reserved, no transaction row, no scheme call. An empty
     * or {@code null} subject list is NOT a free pass — it is recorded as
     * {@link UnscreenedReason#NO_SUBJECT_IDENTITY} against {@link PaymentParty#PAYER}, because "we were
     * given nobody to screen" is exactly the finding that must not disappear.
     *
     * @param paymentRef opaque reference for the evidence anchor (partner txn ref where one exists);
     *                   may be {@code null}
     * @param partnerRef partner code the traffic arrived under; may be {@code null}
     * @param subjects   the parties on this payment
     * @throws PaymentScreeningRefusedException on an authoritative adverse verdict (always), or on an
     *                                          unscreened party when {@code fail-closed} is on
     */
    public void checkNewPayment(@Nullable String paymentRef,
                                @Nullable String partnerRef,
                                @Nullable List<PaymentScreeningSubject> subjects) {
        List<PaymentScreeningSubject> parties = (subjects == null || subjects.isEmpty())
                ? List.of(PaymentScreeningSubject.byReferenceOnly(PaymentParty.PAYER, paymentRef))
                : subjects;

        for (PaymentScreeningSubject subject : parties) {
            if (subject == null) {
                continue;
            }
            screenOne(subject, paymentRef, partnerRef);
        }
    }

    /** Whether an authoritative provider is answering. Reported on the coverage surface. */
    public boolean screeningActive() {
        return port.authoritative();
    }

    /** The configured provider's id ({@code "none"} when nothing is wired). */
    public String providerId() {
        return port.providerId();
    }

    /** Whether the fail-closed kill switch is armed. */
    public boolean failClosed() {
        return failClosed;
    }

    private void screenOne(PaymentScreeningSubject subject,
                           @Nullable String paymentRef,
                           @Nullable String partnerRef) {
        // Order matters: an unscreenable subject is never handed to a provider. A name-matching
        // provider given no name answers "no match", which is indistinguishable from a clean result --
        // the exact confusion gap T1-4 removed from the KYB path.
        if (!port.authoritative()) {
            recordUnscreened(UnscreenedReason.NO_PROVIDER, subject, paymentRef, partnerRef, null);
            return;
        }
        if (!subject.screenable()) {
            recordUnscreened(UnscreenedReason.NO_SUBJECT_IDENTITY, subject, paymentRef, partnerRef,
                    null);
            return;
        }

        ScreeningResult result;
        try {
            result = port.screen(subject);
        } catch (RuntimeException providerFailure) {
            // The port contracts implementations not to throw; if one does, it is an unscreened
            // payment with a named cause, never a silent pass.
            recordUnscreened(UnscreenedReason.PROVIDER_ERROR, subject, paymentRef, partnerRef,
                    providerFailure.getMessage());
            return;
        }
        if (result == null) {
            recordUnscreened(UnscreenedReason.PROVIDER_ERROR, subject, paymentRef, partnerRef,
                    "provider returned null");
            return;
        }

        // An adverse verdict from a real provider is enforced regardless of the fail-closed flag.
        if (result.authoritative()
                && (result.status() == ScreeningResult.Status.HIT
                    || result.status() == ScreeningResult.Status.NEEDS_REVIEW)) {
            raiseHitAlert(subject, result);
            throw PaymentScreeningRefusedException.hit(result.status().name(),
                    result.provenance().providerId());
        }

        if (!result.screeningPerformed()) {
            recordUnscreened(UnscreenedReason.PROVIDER_NOT_AUTHORITATIVE, subject, paymentRef,
                    partnerRef, result.caveat());
            return;
        }
        // Authoritative CLEAR: the only silent path in this method, and the only one a real provider
        // can produce.
        log.debug("screening CLEAR for {} via provider '{}'",
                subject.party(), result.provenance().providerId());
    }

    /**
     * The full unscreened treatment: WARN, metric, durable count, de-duplicated alert — and a refusal
     * only when the operator has armed fail-closed.
     */
    private void recordUnscreened(UnscreenedReason reason,
                                  PaymentScreeningSubject subject,
                                  @Nullable String paymentRef,
                                  @Nullable String partnerRef,
                                  @Nullable String detail) {
        // Attribute PRESENCE, never values: payer names and DOBs must not reach logs (no log
        // aggregation, T3-2) or an alert payload (no column encryption, T5-5).
        log.warn("payment accepted with {} NOT SCREENED ({}: {}){} — subject {} [provider={}]",
                subject.party(), reason, reason.description(),
                detail == null || detail.isBlank() ? "" : " — " + detail,
                subject.attributeSummary(), port.providerId());

        countMetric(reason, subject.party());
        if (counter != null) {
            counter.countUnscreened(reason, subject.party(), port.providerId(), partnerRef, paymentRef);
        }
        raiseUnscreenedAlert(reason, subject.party(), detail);

        if (failClosed) {
            // No float has moved and no scheme has been contacted at this point.
            throw PaymentScreeningRefusedException.unavailable(reason, detail);
        }
    }

    private void countMetric(UnscreenedReason reason, PaymentParty party) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter(METRIC_UNSCREENED,
                    "reason", reason.name(),
                    "party", party.name(),
                    "provider", port.providerId()).increment();
        } catch (RuntimeException e) {
            log.debug("failed to increment {} metric: {}", METRIC_UNSCREENED, e.getMessage());
        }
    }

    /**
     * ONE alert per (reason, party, UTC date). See the class javadoc: with no provider wired every
     * payment is unscreened, so an un-deduplicated alert would be an alert nobody reads.
     */
    private void raiseUnscreenedAlert(UnscreenedReason reason,
                                      PaymentParty party,
                                      @Nullable String detail) {
        if (alerts == null) {
            return;
        }
        Instant now = Instant.now(clock);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        String key = reason.name() + '|' + party.name();
        if (today.equals(alerted.put(key, today))) {
            return;
        }
        emit(new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE,
                ALERT_UNSCREENED,
                "CRITICAL",
                party.name(),
                "payments are being ACCEPTED without a sanctions/PEP screening of the " + party.name()
                        + " party — " + reason.name() + ": " + reason.description()
                        + " (provider='" + port.providerId() + "', gap T5-3). Running total for today:"
                        + " GET /internal/ops/screening-coverage."
                        + (detail == null || detail.isBlank() ? "" : " Detail: " + detail),
                now.toString()));
    }

    /**
     * A real adverse verdict is NOT de-duplicated — every one is its own event needing its own
     * disposition, and there is no volume problem because a provider that produces these is by
     * definition wired.
     */
    private void raiseHitAlert(PaymentScreeningSubject subject, ScreeningResult result) {
        if (alerts == null) {
            return;
        }
        emit(new OpsAlertPayload(
                OpsAlertPayload.EVENT_TYPE,
                ALERT_SANCTIONS_HIT,
                "CRITICAL",
                subject.party().name(),
                "sanctions/PEP screening returned " + result.status().name() + " for the "
                        + subject.party().name() + " party; the payment was REFUSED and needs a"
                        + " compliance disposition. Provider='" + result.provenance().providerId()
                        + "', providerRef='" + result.providerRef() + "'."
                        + " Matched names and list detail are deliberately NOT in this alert — read them"
                        + " from the provider's own decision record.",
                Instant.now(clock).toString()));
    }

    private void emit(OpsAlertPayload payload) {
        try {
            alerts.emit(payload);
        } catch (RuntimeException alertFailure) {
            // Alerting must never break the pay path.
            log.warn("failed to raise {} alert: {}", payload.alertType(), alertFailure.getMessage());
        }
    }
}
