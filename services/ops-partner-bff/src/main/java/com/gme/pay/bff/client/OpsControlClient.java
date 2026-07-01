package com.gme.pay.bff.client;

import com.gme.pay.contracts.OperationalStatusView;

/**
 * Read + control view of the platform's operational / kill-switch state, owned by
 * config-registry (the Operations-features wave ops-console surface). Production calls
 * config-registry's new ops endpoints; the Phase-1 default
 * {@link com.gme.pay.bff.client.stub.StubOpsControlClient} is an in-memory kill-switch
 * so the BFF (and the control-tower aggregation) boots standalone.
 *
 * <p>Endpoint mapping (config-registry ops):
 * <ul>
 *   <li>{@code GET  /v1/ops/operational-status} -> {@link #operationalStatus()}</li>
 *   <li>{@code POST /v1/ops/pause}       -> {@link #pause(String, String)}</li>
 *   <li>{@code POST /v1/ops/resume}      -> {@link #resume(String)}</li>
 *   <li>{@code POST /v1/ops/maintenance} -> {@link #maintenance(String, String)}</li>
 *   <li>{@code POST /v1/ops/suspend}     -> {@link #suspend(String, String, String, String)}</li>
 *   <li>{@code POST /v1/ops/unsuspend}   -> {@link #unsuspend(String, String, String)}</li>
 * </ul>
 *
 * <p>Every mutator returns the fresh {@link OperationalStatusView} after the change so
 * the Admin UI re-renders from the authoritative post-state.
 */
public interface OpsControlClient {

    /**
     * The current operational status. Never throws for the read — degrades to
     * {@link OperationalStatusView#allClear()} when config-registry is unreachable,
     * so the control-tower shows an honest "unknown/clear" rather than a 500.
     */
    OperationalStatusView operationalStatus();

    /** Engage the global master kill switch (accept nothing new). */
    OperationalStatusView pause(String actor, String reason);

    /** Release the global master kill switch. */
    OperationalStatusView resume(String actor);

    /** Enter/leave maintenance mode (softer, degraded/read-mostly). */
    OperationalStatusView maintenance(String actor, String reason);

    /**
     * Suspend one entity. {@code scope} is one of {@code partner} | {@code scheme} |
     * {@code route}; {@code ref} the entity id.
     */
    OperationalStatusView suspend(String scope, String ref, String actor, String reason);

    /** Lift a suspension for one entity. */
    OperationalStatusView unsuspend(String scope, String ref, String actor);
}
