package com.gme.pay.payment.domain;

import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.payment.domain.client.OperationalStatusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class OperationalGate {

    private static final Logger log = LoggerFactory.getLogger(OperationalGate.class);

    private final OperationalStatusClient statusClient;

    public OperationalGate(OperationalStatusClient statusClient) {
        this.statusClient = statusClient;
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
     */
    public void checkNewAuthorization(String partnerRef, String schemeRef, String routeRef) {
        OperationalStatusView status = statusClient.currentStatus();
        if (status == null) {
            // Defensive: a null status is treated as all-clear (the client is contracted to apply the
            // fail-open / fail-closed policy and never return null, but we never NPE the pay path).
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
    }

    /** Convenience overload for the wallet path, which gates by partner alias only. */
    public void checkNewAuthorization(String partnerRef) {
        checkNewAuthorization(partnerRef, null, null);
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
