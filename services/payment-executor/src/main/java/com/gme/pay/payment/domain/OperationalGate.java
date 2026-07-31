package com.gme.pay.payment.domain;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.kyb.PaymentScreeningSubject;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * The Operations <b>operational gate</b> — checked at the START of a NEW payment authorization to
 * refuse new work while the platform is globally paused / in maintenance, or when the partner /
 * scheme / route resolved for THIS payment is individually suspended.
 *
 * <p><b>Scope.</b> Only NEW authorizations are gated: the wallet {@code POST /v1/pay} entry point
 * (GMEREMIT / SENDMN inbound + the FailoverPaymentRouter outbound branch) and the orchestrated
 * {@code POST /v1/payments/authorize}. Confirm/capture of an ALREADY-authorized txn, refunds and
 * status lookups are NEVER gated — in-flight payments must complete even mid-pause.
 *
 * <p><b>Precedence.</b> {@code systemPaused} → {@code maintenanceMode} → partner → scheme → route.
 * The first match throws {@link OperationalGateException} carrying a stable canonical code.
 *
 * <p>The status is read via {@link OperationalStatusClient}, which applies the fail-open / fail-closed
 * policy on an unreachable config-registry — this gate never inspects reachability itself.
 *
 * <h2>T3-6: the scheduled window is checked HERE, on purpose</h2>
 * Two kinds of unavailability now meet in this one method: the MANUAL operator hold (this class) and
 * the scheme's own published OPERATING WINDOW ({@link SchemeOperatingHoursGate}, {@code
 * scheme_operating_hours} V024). The schedule check is composed into {@link #checkNewAuthorization}
 * rather than bolted onto each controller so that <b>both</b> new-payment entry points — the
 * orchestrated {@code POST /v1/payments/authorize} and the wallet {@code POST /v1/pay} — are covered by
 * construction. Gap T4-2 was exactly the failure of adding a rule to one entry point and not the other;
 * a new caller of this gate inherits both checks and cannot re-open that split.
 *
 * <p>Order is deliberate: the operator's kill switch is evaluated FIRST. A paused platform should say
 * "paused", not "that rail is closed", and an operator hold outranks a schedule.
 *
 * <h2>T5-3: sanctions/PEP screening joins the same method, for the same reason</h2>
 * A third kind of "may this payment proceed" now composes in here: the counterparty screening seam
 * ({@link PaymentScreeningGate}). It is placed LAST of the three because the other two are cheap local
 * decisions about the platform's own state, while screening is the one that may call a vendor — there
 * is no point paying for a provider round-trip on a payment a pause was going to refuse anyway. As with
 * T3-6, composing it into {@link #checkNewAuthorization} rather than into each controller is what makes
 * <b>both</b> new-payment entry points covered by construction, so gap T4-2 cannot recur.
 */
@Component
public class OperationalGate {

    private static final Logger log = LoggerFactory.getLogger(OperationalGate.class);

    private final OperationalStatusClient statusClient;
    /**
     * T3-6 scheduled-window check. {@code @Nullable} so the many unit slices that construct this gate
     * with only a status supplier keep compiling and keep their exact behaviour (no schedule check).
     */
    @Nullable private final SchemeOperatingHoursGate hoursGate;
    /**
     * T5-3 counterparty screening seam. {@code @Nullable} for the same reason as {@link #hoursGate}:
     * the existing unit slices that construct this gate with only a status supplier must keep their
     * exact behaviour. Production wiring always supplies it.
     */
    @Nullable private final PaymentScreeningGate screeningGate;

    @Autowired
    public OperationalGate(OperationalStatusClient statusClient,
                           @Nullable SchemeOperatingHoursGate hoursGate,
                           @Nullable PaymentScreeningGate screeningGate) {
        this.statusClient = statusClient;
        this.hoursGate = hoursGate;
        this.screeningGate = screeningGate;
    }

    /**
     * Status + operating-hours gate with no screening seam — retained for the slices written against
     * T3-6's two-argument shape.
     */
    public OperationalGate(OperationalStatusClient statusClient,
                           @Nullable SchemeOperatingHoursGate hoursGate) {
        this(statusClient, hoursGate, null);
    }

    /**
     * Status-only gate (no operating-hours check) — used by unit slices that are testing suspension
     * behaviour. Production wiring always supplies the {@link SchemeOperatingHoursGate}.
     */
    public OperationalGate(OperationalStatusClient statusClient) {
        this(statusClient, null, null);
    }

    /**
     * Gate a NEW authorization by its resolved routing references. Any argument may be {@code null}
     * (unresolved at gate time — e.g. the wallet path gates by partner alias before the scheme is
     * chosen); {@code null} references simply skip their per-entity check. Matching is
     * case-insensitive and trims surrounding whitespace so operator-entered lists line up with the
     * codes carried on the request.
     *
     * @throws OperationalGateException when the platform is paused / in maintenance, or the given
     *                                  partner / scheme / route is suspended.
     * @throws SchemeClosedException    (T3-6) when {@code schemeRef} is outside its seeded operating
     *                                  window; raised after the suspension checks and still before any
     *                                  side effect.
     */
    public void checkNewAuthorization(String partnerRef, String schemeRef, String routeRef) {
        checkNewAuthorization(partnerRef, schemeRef, routeRef, null, null);
    }

    /**
     * As {@link #checkNewAuthorization(String, String, String)} plus the <b>T5-3</b> counterparty
     * screening of this payment's parties.
     *
     * <p><b>Passing no subjects is not a bypass.</b> A {@code null}/empty {@code subjects} list is
     * recorded by the screening gate as {@link com.gme.pay.kyb.UnscreenedReason#NO_SUBJECT_IDENTITY}
     * against the payer — "we were handed nobody to screen" is the finding, not an exemption. This is
     * deliberately the opposite convention to {@code schemeRef}, where {@code null} means "not resolved
     * yet, so assert nothing": an unresolved scheme is a routing fact that a later per-candidate check
     * covers, whereas an absent counterparty identity is the gap itself.
     *
     * @param paymentRef opaque reference for the coverage record's evidence anchor (a partner txn ref
     *                   where the entry point has one); may be {@code null}
     * @param subjects   the parties on this payment; may be {@code null}
     * @throws PaymentScreeningRefusedException on an authoritative adverse verdict (always), or on an
     *                                          unscreened party when {@code gmepay.screening.fail-closed}
     *                                          is on. Raised after the suspension and window checks and
     *                                          still before any side effect.
     */
    public void checkNewAuthorization(String partnerRef, String schemeRef, String routeRef,
                                      @Nullable String paymentRef,
                                      @Nullable List<PaymentScreeningSubject> subjects) {
        OperationalStatusView status = statusClient.currentStatus();
        if (status == null) {
            // Defensive: a null status is treated as all-clear (the client is contracted to apply the
            // fail-open / fail-closed policy and never return null, but we never NPE the pay path).
            checkOperatingWindow(schemeRef);
            checkScreening(paymentRef, partnerRef, subjects);
            return;
        }

        if (status.systemPaused()) {
            throw paused("platform is paused", status);
        }
        if (status.maintenanceMode()) {
            throw paused("platform is in maintenance", status);
        }
        if (contains(status.suspendedPartners(), partnerRef)) {
            throw new OperationalGateException(OperationalGateException.PARTNER_SUSPENDED,
                    "partner '" + partnerRef + "' is currently suspended"
                            + reasonSuffix(status));
        }
        if (contains(status.suspendedSchemes(), schemeRef)) {
            throw new OperationalGateException(OperationalGateException.SCHEME_SUSPENDED,
                    "scheme '" + schemeRef + "' is currently suspended"
                            + reasonSuffix(status));
        }
        if (contains(status.suspendedRoutes(), routeRef)) {
            throw new OperationalGateException(OperationalGateException.ROUTE_SUSPENDED,
                    "route '" + routeRef + "' is currently suspended"
                            + reasonSuffix(status));
        }

        // T3-6: nothing operator-driven objects. Now ask the scheme's own published schedule.
        checkOperatingWindow(schemeRef);

        // T5-3: the platform is willing and the rail is open. Last question: were the counterparties
        // screened? (Last of the three so no vendor round-trip is paid for a payment already refused.)
        checkScreening(paymentRef, partnerRef, subjects);
    }

    /** Convenience overload for the wallet path, which gates by partner alias only. */
    public void checkNewAuthorization(String partnerRef) {
        checkNewAuthorization(partnerRef, null, null);
    }

    /**
     * T3-6 scheduled-window check. A null gate (unit slices) or a null/blank {@code schemeRef} (the
     * scheme is not resolved at this point) asserts nothing.
     */
    private void checkOperatingWindow(@Nullable String schemeRef) {
        if (hoursGate != null) {
            hoursGate.checkNewPayment(schemeRef);
        }
    }

    /**
     * T5-3 counterparty screening. A null gate (unit slices) asserts nothing; a null subject list does
     * NOT — see {@link #checkNewAuthorization(String, String, String, String, List)}.
     */
    private void checkScreening(@Nullable String paymentRef,
                                @Nullable String partnerRef,
                                @Nullable List<PaymentScreeningSubject> subjects) {
        if (screeningGate != null) {
            screeningGate.checkNewPayment(paymentRef, partnerRef, subjects);
        }
    }

    private OperationalGateException paused(String what, OperationalStatusView status) {
        log.warn("operational gate: rejecting new authorization — {}{}", what, reasonSuffix(status));
        return new OperationalGateException(OperationalGateException.SYSTEM_PAUSED,
                what + " — new payments are not being accepted" + reasonSuffix(status));
    }

    private static String reasonSuffix(OperationalStatusView status) {
        return status.reason() != null && !status.reason().isBlank()
                ? " (" + status.reason() + ")"
                : "";
    }

    private static boolean contains(List<String> suspended, String ref) {
        if (suspended == null || suspended.isEmpty() || ref == null || ref.isBlank()) {
            return false;
        }
        String needle = ref.trim().toLowerCase(Locale.ROOT);
        for (String s : suspended) {
            if (s != null && s.trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
