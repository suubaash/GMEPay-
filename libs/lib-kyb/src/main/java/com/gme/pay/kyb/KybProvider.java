package com.gme.pay.kyb;

/**
 * Vendor-agnostic KYB / sanctions-screening port (ADR-009).
 *
 * <p>The platform never binds to a specific vendor at the code level — a vendor
 * swap (or a future multi-vendor consensus {@code CompositeKybProvider} for
 * high-risk partners) is a configuration change, not a refactor. The concrete
 * adapter for the chosen vendor ({@code OctaKybAdapter}, ADR-014) lives in
 * {@code services/kyb-adapter}; the deterministic {@link StubKybAdapter} lives
 * here so every module can test against the port without vendor access.
 *
 * <p>Implementations must be side-effect free with respect to the platform's
 * own state: persisting the result to {@code partner_kyb} and publishing the
 * {@code gmepay.kyb.screening} event are the CALLER's responsibility (ADR-007
 * audit + ADR-001 fan-out happen where the transaction is).
 *
 * <p>ADR-009 also names an {@code subscribe(PartnerId, ScreeningPolicy)}
 * ongoing-monitoring operation; that lands with the real vendor adapter (it is
 * meaningless for a stateless stub) and is deliberately absent here until the
 * Octa Solution sandbox contract (ADR-014) defines the callback shape.
 */
public interface KybProvider {

    /**
     * Sanctions / PEP / adverse-media screening of the subject (entity + every
     * UBO). Synchronous; vendors with async APIs adapt by polling inside their
     * adapter so callers see one blocking call.
     */
    ScreeningResult screen(KybSubject subject);

    /**
     * Full KYB run: {@link #screen screening} plus license / UBO / corporate
     * registry checks. Heavier and slower than {@link #screen}; the wizard's
     * "Run screening" button calls {@link #screen}, the activation gate (Slice
     * 8) calls this.
     */
    KybRunResult runFullKyb(KybSubject subject);
}
