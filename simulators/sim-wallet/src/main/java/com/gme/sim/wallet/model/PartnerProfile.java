package com.gme.sim.wallet.model;

/**
 * The two partner profiles supported by this simulator.
 *
 * GMEREMIT – domestic Korean use-case (UC-01/UC-03-01):
 *   wallet currency KRW, no prefunding, no FX.
 *
 * SENDMN – overseas Mongolian use-case (UC-02/UC-03-02):
 *   wallet currency MNT, prefunding model, FX 2% margin applied.
 */
public enum PartnerProfile {
    GMEREMIT,
    SENDMN
}
