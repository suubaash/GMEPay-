package com.gme.sim.scheme.model;

/**
 * FSM states for a scheme payment.
 *
 * Legal transitions:
 *   AUTHORIZED  → CAPTURED   (via /commit)
 *   CAPTURED    → REFUNDED   (via /refund)
 * All other transitions are illegal → 409 CONFLICT.
 */
public enum PaymentState {
    AUTHORIZED,
    CAPTURED,
    REFUNDED
}
