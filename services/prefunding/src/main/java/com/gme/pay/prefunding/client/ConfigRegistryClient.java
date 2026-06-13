package com.gme.pay.prefunding.client;

/**
 * Prefunding's narrow view of config-registry: on a balance BREACH (balance &lt; 0) the
 * {@link com.gme.pay.prefunding.alert.TierAlertEvaluator} proposes an auto-suspension of the
 * partner via the 4-eyes change_request queue (ADR-008) — {@code POST /v1/change-requests}
 * with {@code aggregateType=partner}, {@code payload {"status":"SUSPENDED"}} and
 * {@code proposedBy='system'} (the {@code (system, system)} carve-out lets ops bots both
 * propose and, where configured, approve).
 *
 * <p>Default wiring is the in-memory {@link StubConfigRegistryClient}; setting
 * {@code gmepay.config-registry.client=rest} activates {@link RestConfigRegistryClient}
 * against {@code gmepay.config-registry.base-url} — the same convention the BFF uses.
 */
public interface ConfigRegistryClient {

    /** The maker recorded on system-raised change requests. */
    String SYSTEM_PROPOSER = "system";

    /**
     * Propose (not apply!) suspending {@code partnerCode}. The change request lands in the
     * PROPOSED state and awaits checker review — auto-suspend is still 4-eyes guarded.
     *
     * <p>Must not throw on upstream failure: a config-registry outage must never roll back
     * the balance mutation that detected the breach. Implementations log and swallow.
     */
    void proposePartnerSuspension(String partnerCode, String reason);
}
