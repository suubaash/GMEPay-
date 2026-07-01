package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.OpsControlClient;
import com.gme.pay.contracts.OperationalStatusView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-1 in-memory kill-switch stub of {@link OpsControlClient}. Holds the
 * operational state in memory so the BFF (and the control-tower aggregation) boots
 * standalone and each operator-action endpoint exercises a real state transition
 * without config-registry running.
 *
 * <p>Default bean: wired unless {@code gmepay.ops-control.client=rest} selects the
 * live {@link com.gme.pay.bff.client.rest.RestOpsControlClient}.
 *
 * <p>Starts ALL-CLEAR. Mutations are synchronized so concurrent tests are stable.
 */
@Component
@ConditionalOnProperty(
        name = "gmepay.ops-control.client",
        havingValue = "stub",
        matchIfMissing = true)
public class StubOpsControlClient implements OpsControlClient {

    private boolean systemPaused = false;
    private boolean maintenanceMode = false;
    private final List<String> suspendedPartners = new ArrayList<>();
    private final List<String> suspendedSchemes = new ArrayList<>();
    private final List<String> suspendedRoutes = new ArrayList<>();
    private String reason;

    @Override
    public synchronized OperationalStatusView operationalStatus() {
        return snapshot();
    }

    @Override
    public synchronized OperationalStatusView pause(String actor, String reason) {
        this.systemPaused = true;
        this.reason = reason;
        return snapshot();
    }

    @Override
    public synchronized OperationalStatusView resume(String actor) {
        this.systemPaused = false;
        this.reason = null;
        return snapshot();
    }

    @Override
    public synchronized OperationalStatusView maintenance(String actor, String reason) {
        this.maintenanceMode = !this.maintenanceMode;
        this.reason = this.maintenanceMode ? reason : null;
        return snapshot();
    }

    @Override
    public synchronized OperationalStatusView suspend(String scope, String ref, String actor, String reason) {
        listFor(scope).remove(ref);
        listFor(scope).add(ref);
        this.reason = reason;
        return snapshot();
    }

    @Override
    public synchronized OperationalStatusView unsuspend(String scope, String ref, String actor) {
        listFor(scope).remove(ref);
        return snapshot();
    }

    private List<String> listFor(String scope) {
        return switch (scope == null ? "" : scope.toLowerCase()) {
            case "scheme" -> suspendedSchemes;
            case "route" -> suspendedRoutes;
            default -> suspendedPartners; // "partner" and any unknown scope default to partner
        };
    }

    private OperationalStatusView snapshot() {
        boolean anyState = systemPaused || maintenanceMode
                || !suspendedPartners.isEmpty() || !suspendedSchemes.isEmpty() || !suspendedRoutes.isEmpty();
        return new OperationalStatusView(
                systemPaused,
                maintenanceMode,
                List.copyOf(suspendedPartners),
                List.copyOf(suspendedSchemes),
                List.copyOf(suspendedRoutes),
                anyState ? reason : null,
                anyState ? Instant.now().toString() : null);
    }
}
