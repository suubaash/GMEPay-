package com.gme.pay.payment.domain;

import com.gme.pay.contracts.SchemeAvailability;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.contracts.events.OpsAlertPayload;
import com.gme.pay.payment.alert.OpsAlertPipeline;
import com.gme.pay.payment.domain.client.SchemeOperatingHoursClient;
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
 * Enforces a scheme's operating window on the NEW-payment path — gap <b>T3-6</b>.
 *
 * <p>{@code scheme_operating_hours} (V024) has existed, seeded and readable, with the migration header
 * stating outright that "the router and the settlement calculator need this to decide 'can this
 * transaction route NOW?'". A repo-wide grep for {@code OperatingHours} found ZERO consumers on the
 * payment path. This gate is that consumer.
 *
 * <h2>Three verdicts, never two</h2>
 * The decision comes from the pure {@code SchemeAvailability.evaluate}, which reuses the philosophy the
 * settlement {@code BusinessCalendar} established for T3-4: missing reference data is its own answer.
 *
 * <table>
 *   <caption>Policy</caption>
 *   <tr><th>Verdict</th><th>Action</th><th>Why</th></tr>
 *   <tr><td>{@code OPEN}</td><td>proceed silently</td><td>a row affirms the rail is accepting traffic</td></tr>
 *   <tr><td>{@code CLOSED}</td><td>throw {@link SchemeClosedException}</td>
 *       <td>a row affirms the rail is shut; sending anyway means a certain failure at the scheme, or
 *           worse an accepted-then-unsettleable payment</td></tr>
 *   <tr><td>{@code UNVERIFIED}</td><td><b>proceed</b> + WARN + ops alert</td>
 *       <td>the deliberate safe default (see below)</td></tr>
 * </table>
 *
 * <h2>Why UNVERIFIED is fail-OPEN here, stated plainly</h2>
 * Only 5 of the 9 rostered schemes are seeded in V024 (QRIS and KHQR are explicitly deferred to "the
 * corridors that first enable them", and NEPAL / SENDMN — two of the three LIVE adapters — have no rows
 * at all). Rejecting on UNVERIFIED would therefore take the Nepal and Mongolia corridors <b>offline the
 * moment this gate shipped</b>, because of absent reference data rather than any operational fact. That
 * is a worse outcome than routing to a rail whose published hours nobody has recorded. So the choice is:
 * <b>permit, and make it impossible to miss</b> — a WARN line naming the scheme plus a
 * {@code SCHEME_HOURS_UNVERIFIED} ops alert through the same T3-3 pipeline that carries
 * {@code DECLINE_SPIKE}, de-duplicated to one per (scheme, UTC date) so the alert stream stays readable.
 * This is the identical call the settlement calendar made (proceed on UNVERIFIED, stamp the verdict,
 * raise {@code BATCH_CALENDAR_UNVERIFIED}), and it matches this module's own existing convention that
 * "no config row" is permissive while "a config row we cannot evaluate" is not
 * ({@link WalletLimitGate}'s {@code resolveLimits} contract).
 *
 * <p>The corollary — the reason this is not a silent assumption — is that a row we CAN read and that
 * says CLOSED is enforced without exception, and a missing row is visible in logs and in the alert
 * stream on every UTC date it happens. Nothing anywhere concludes "no row means open".
 *
 * <h2>Cutoff ≠ close (task 6)</h2>
 * This gate reads {@code close_time_local} only. {@code cutoff_time_local} is the daily SETTLEMENT
 * cutoff — ZEROPAY is a 24x7 rail with a 16:30 KST KFTC cutoff — so treating it as a close would shut
 * the platform's only live corridor for 7.5 hours a day. A past-cutoff payment is logged at debug (it
 * books to the next value date) and never rejected. Settlement's own cutoff behaviour
 * ({@code SettlementConfigService.DEFAULT_CUTOFF_TIME} = 16:30 Asia/Seoul, V013) is untouched.
 *
 * <h2>What is NOT gated</h2>
 * Confirm/capture, cancel and refund. See {@link SchemeClosedException} and {@code OperationalGate}:
 * an in-flight payment must complete and a refund must be possible after the window closes.
 */
@Component
public class SchemeOperatingHoursGate {

    private static final Logger log = LoggerFactory.getLogger(SchemeOperatingHoursGate.class);

    /** Ops-alert type raised when a scheme's window cannot be verified. */
    public static final String ALERT_UNVERIFIED_WINDOW = "SCHEME_HOURS_UNVERIFIED";

    /** Banner logged when enforcement is switched off by configuration. */
    static final String DISABLED_BANNER =
            "SCHEME OPERATING HOURS ENFORCEMENT IS OFF: 'gmepay.scheme-hours.enforcement-enabled=false'."
                    + " Payments to a CLOSED scheme will be accepted. This is a deliberate override —"
                    + " remove it to restore gap T3-6 enforcement.";

    private final SchemeOperatingHoursClient hoursClient;
    @Nullable private final OpsAlertPipeline alerts;
    private final boolean enforcementEnabled;
    private final Clock clock;

    /** scheme → last UTC date an UNVERIFIED alert was raised for it (alert de-duplication). */
    private final Map<String, LocalDate> unverifiedAlerted = new ConcurrentHashMap<>();

    @Autowired
    public SchemeOperatingHoursGate(
            SchemeOperatingHoursClient hoursClient,
            @Nullable OpsAlertPipeline alerts,
            @Value("${gmepay.scheme-hours.enforcement-enabled:true}") boolean enforcementEnabled) {
        this(hoursClient, alerts, enforcementEnabled, Clock.systemUTC());
    }

    /** Test constructor — explicit clock so a closed window / a day boundary is reproducible. */
    public SchemeOperatingHoursGate(SchemeOperatingHoursClient hoursClient,
                                    @Nullable OpsAlertPipeline alerts,
                                    boolean enforcementEnabled,
                                    Clock clock) {
        this.hoursClient = hoursClient;
        this.alerts = alerts;
        this.enforcementEnabled = enforcementEnabled;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @PostConstruct
    void warnIfDisabled() {
        if (!enforcementEnabled) {
            log.error("!!! {}", DISABLED_BANNER);
        }
    }

    /**
     * Gate a NEW payment aimed at {@code schemeRef}.
     *
     * <p>A {@code null}/blank {@code schemeRef} means the caller has not resolved a scheme yet and is
     * therefore asserting nothing — the gate evaluates nothing rather than inventing a subject. (The
     * wallet's cross-border branch is gated per RESOLVED candidate inside
     * {@link FailoverPaymentRouter}, so no entry point relies on a guessed scheme code.)
     *
     * @throws SchemeClosedException when the scheme's seeded window affirmatively excludes now
     */
    public void checkNewPayment(@Nullable String schemeRef) {
        evaluate(schemeRef); // verdict acted on inside; return value is for callers that need it
    }

    /**
     * As {@link #checkNewPayment} but returns the evaluation, for a caller that wants to branch on it
     * (e.g. skipping a closed candidate during failover instead of failing the whole payment).
     *
     * @throws SchemeClosedException when the window excludes now AND enforcement is enabled
     */
    public SchemeAvailability evaluate(@Nullable String schemeRef) {
        if (schemeRef == null || schemeRef.isBlank()) {
            return SchemeAvailability.evaluate(schemeRef, List.of(), Instant.now(clock));
        }
        String scheme = SchemeId.canonicalCode(schemeRef);
        String subject = scheme == null ? schemeRef.trim() : scheme;
        Instant now = Instant.now(clock);

        List<SchemeOperatingHoursView> rows = readSchedule(subject);
        SchemeAvailability availability = SchemeAvailability.evaluate(subject, rows, now);

        switch (availability.verdict()) {
            case CLOSED -> {
                if (!enforcementEnabled) {
                    log.error("scheme '{}' is CLOSED but enforcement is disabled — allowing: {}",
                            subject, availability.reason());
                    return availability;
                }
                // No float has moved and no scheme has been contacted at this point.
                log.warn("rejecting new payment — {}", availability.reason());
                throw new SchemeClosedException(availability);
            }
            case UNVERIFIED -> reportUnverified(subject, availability, now);
            case OPEN -> {
                if (availability.pastCutoff()) {
                    log.debug("scheme '{}' is open and PAST its {} settlement cutoff — the payment"
                                    + " books to the next value date (not a rejection)",
                            subject, availability.cutoffTimeLocal());
                }
            }
            default -> { }
        }
        return availability;
    }

    /** Never let a reference-data read failure become a payment failure. */
    private List<SchemeOperatingHoursView> readSchedule(String scheme) {
        try {
            List<SchemeOperatingHoursView> rows = hoursClient.weeklySchedule(scheme);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException ex) {
            // The port contracts implementations not to throw; if one does, degrade to UNVERIFIED
            // (visible) rather than 500-ing a payment over a schedule lookup.
            log.warn("operating-hours lookup for '{}' threw ({}) — treating window as UNVERIFIED",
                    scheme, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Make an unverified window impossible to miss: a WARN line every time, plus ONE ops alert per
     * (scheme, UTC date) so a permanently unseeded corridor does not drown the alert stream.
     */
    private void reportUnverified(String scheme, SchemeAvailability availability, Instant now) {
        log.warn("scheme '{}' operating window is UNVERIFIED — allowing the payment and recording the"
                + " fact (NOT assuming open): {}", scheme, availability.reason());
        if (alerts == null) {
            return;
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate previous = unverifiedAlerted.put(scheme, today);
        if (today.equals(previous)) {
            return;
        }
        try {
            alerts.emit(new OpsAlertPayload(
                    OpsAlertPayload.EVENT_TYPE,
                    ALERT_UNVERIFIED_WINDOW,
                    "WARN",
                    scheme,
                    "no usable scheme_operating_hours row (V024) — payments to this scheme are being"
                            + " accepted with an UNVERIFIED operating window: " + availability.reason(),
                    now.toString()));
        } catch (RuntimeException alertFailure) {
            // Alerting must never break the pay path.
            log.warn("failed to raise {} alert for {}: {}",
                    ALERT_UNVERIFIED_WINDOW, scheme, alertFailure.getMessage());
        }
    }
}
