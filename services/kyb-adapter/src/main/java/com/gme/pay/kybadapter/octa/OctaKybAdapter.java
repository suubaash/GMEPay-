package com.gme.pay.kybadapter.octa;

import com.gme.pay.kyb.KybProvider;
import com.gme.pay.kyb.KybRunResult;
import com.gme.pay.kyb.KybSubject;
import com.gme.pay.kyb.ScreeningResult;

/**
 * Octa Solution adapter placeholder (ADR-014).
 *
 * <p>Octa Solution (octasolution.co.kr) is the chosen KYB / sanctions vendor:
 * Korean regulatory coverage (KoFIU consolidated lists, MOFA-KR sanctions, FSS
 * adverse-media feeds), REST sandbox + production APIs, PIPA-compliant data
 * residency. The API specification and sandbox credentials are a Slice 3
 * calendar dependency — until they arrive this class deliberately fails fast
 * so a mis-wired {@code gmepay.kyb.provider=octa} environment is caught at the
 * first screening call instead of silently producing fake results.
 *
 * <p>TODO(ADR-014): implement against the Octa Solution sandbox once the user
 * provides the API spec + credentials:
 * <ul>
 *   <li>{@link #screen} → Octa screening endpoint (entity + UBO fan-out),
 *       mapping their match schema onto {@link ScreeningResult.Hit};</li>
 *   <li>{@link #runFullKyb} → Octa full CDD run (license / UBO / registry);</li>
 *   <li>ongoing-monitoring subscription (the {@code subscribe} port operation
 *       deferred in {@link KybProvider}) → their webhook callback contract;</li>
 *   <li>raw vendor responses archived to the MinIO vault per ADR-006.</li>
 * </ul>
 */
public class OctaKybAdapter implements KybProvider {

    private final String baseUrl;
    private final String apiKey;

    /**
     * @param baseUrl Octa Solution API base URL ({@code gmepay.kyb.octa.base-url}).
     * @param apiKey  tenant API key ({@code gmepay.kyb.octa.api-key}).
     */
    public OctaKybAdapter(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public ScreeningResult screen(KybSubject subject) {
        throw notYetAvailable();
    }

    @Override
    public KybRunResult runFullKyb(KybSubject subject) {
        throw notYetAvailable();
    }

    /** Configured base URL (visible for wiring assertions; never logged with the key). */
    public String baseUrl() {
        return baseUrl;
    }

    private UnsupportedOperationException notYetAvailable() {
        return new UnsupportedOperationException(
                "Octa Solution sandbox credentials pending — ADR-014");
    }
}
