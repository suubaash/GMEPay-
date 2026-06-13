package com.gme.pay.contracts;

/**
 * Response body of a lifecycle transition that PROVISIONED credentials
 * (Slice 8: first entry into SANDBOX or LIVE) — the partner's post-transition
 * view plus the ONE-TIME plaintext bundle.
 *
 * <p>⚠ {@code credentials} is the only copy of the plaintext that will ever
 * exist (SEC-09 §4): auth-identity stores salted hashes, config-registry's
 * ledger stores prefix + last-4. Renderers show it once with a copy
 * affordance and drop it; intermediaries (BFF, gateways) must not log
 * response bodies on this route. Transitions that issue nothing (e.g. a
 * REACTIVATE that finds its keys already ACTIVE) return a plain
 * {@link PartnerView} instead of this wrapper.
 */
public record PartnerActivationView(
        PartnerView partner,
        IssuedCredentialBundle credentials) {
}
