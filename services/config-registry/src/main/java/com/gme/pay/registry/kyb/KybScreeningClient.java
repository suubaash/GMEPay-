package com.gme.pay.registry.kyb;

import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;

/**
 * config-registry's seam to the kyb-adapter service (ADR-009).
 *
 * <p>MSA rule: this service never embeds vendor logic — screening happens in
 * {@code services/kyb-adapter} behind {@code POST /v1/kyb/screen}. The
 * production implementation ({@link RestKybClient}) calls that endpoint; the
 * default ({@link StubKybClient}) runs lib-kyb's deterministic
 * {@code StubKybAdapter} in-process so unit slices and local dev work without
 * the adapter service running — the same rest-vs-stub wiring contract as the
 * BFF's {@code ConfigRegistryClient}.
 */
public interface KybScreeningClient {

    /** Screen one subject; implementations surface failures as runtime exceptions. */
    ScreeningResult screen(KybSubject subject);
}
