package com.gme.pay.registry.kyb;

/**
 * config-registry's seam to kyb-adapter's FULL verification run
 * ({@code POST /v1/kyb/verify}) — the document-aware counterpart to
 * {@link KybScreeningClient#screen} (raw sanctions screening only). Mirrors the
 * screen seam's rest-vs-stub discipline: {@link RestKybVerifyClient} (active when
 * {@code gmepay.kyb-adapter.client=rest}) and {@link StubKybVerifyClient} (the
 * default — local dev / unit slices verify in-process).
 */
public interface KybVerifyClient {

    /**
     * Run a full KYB verification for the assembled subject; implementations
     * surface upstream failures as runtime exceptions (the verify call is an
     * explicit operator action, so a failed run must be visible, not silently
     * null).
     */
    KybVerificationResult verify(KybVerificationRequest request);
}
