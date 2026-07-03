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

    /**
     * Read one platform setting's raw value from config-registry's generic settings store
     * ({@code GET /v1/admin/settings/{key}}), or {@code null} when the key is absent or the
     * store is unreachable. Used by {@link com.gme.pay.prefunding.alert.TierAlertEvaluator}
     * to read the float low-balance alert tier boundaries at runtime, always with a
     * code-side fallback default when this returns {@code null}.
     *
     * <p>Like {@link #proposePartnerSuspension}, must never throw: the evaluator runs inside
     * the balance-mutation transaction and an alert must never fail because a tunable could
     * not be fetched. Implementations log and return {@code null} on any error/absence.
     *
     * @param key the dotted setting key (e.g. {@code prefunding.alert.tier1.pct})
     * @return the setting value as a string, or {@code null} to signal "use the fallback"
     */
    default String getSettingValue(String key) {
        return null;
    }
}
