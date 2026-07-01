package com.gme.pay.router.web;

import com.gme.pay.contracts.PartnerSchemeView;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.router.resolve.LocationSchemeQuery;
import com.gme.pay.router.resolve.LocationSchemeResolver;
import com.gme.pay.router.resolve.PaymentMode;
import com.gme.pay.router.resolve.SchemeResolution;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/route/resolve?country=KR&mode=MPM&direction=INBOUND} — the
 * data-driven scheme-for-location endpoint qr-service consumes in place of its
 * config-driven country allow-list. Failures throw the canonical
 * {@link ApiException} ({@link ErrorCode}) and are rendered as the unified
 * API-05 {@code ApiError} envelope by {@link RouterApiExceptionHandler}
 * (409 for mode/direction, 404 for no-scheme, 400 for validation).
 */
@RestController
@RequestMapping("/v1/route/resolve")
public class LocationResolveController {

    private final LocationSchemeResolver resolver;

    public LocationResolveController(LocationSchemeResolver resolver) {
        this.resolver = resolver;
    }

    /** Resolution response body: chosen scheme + the full candidate set + ambiguity flag. */
    public record ResolveResponse(String scheme, List<String> candidates, boolean ambiguous) {
        static ResolveResponse from(SchemeResolution r) {
            return new ResolveResponse(r.scheme(), r.candidates(), r.ambiguous());
        }
    }

    /**
     * {@code GET /v1/route/resolve?network=&country=&mode=&direction=}.
     *
     * <p>Two shapes on one endpoint, selected by the OPTIONAL {@code network} param:
     * <ul>
     *   <li><b>network present</b> (ADR-016 QR-classified failover) — returns the
     *       ORDERED candidate list ({@code List<PartnerSchemeView>}, ascending
     *       priority, ACTIVE only) of partner_scheme rows whose
     *       {@code networkIdentifier} CSV contains the network AND match
     *       country/mode/direction. Element 0 is the primary; the rest are the
     *       failover order.</li>
     *   <li><b>network absent</b> — the pre-ADR-016 country-based
     *       {@link ResolveResponse} (chosen scheme + candidate set + ambiguity),
     *       unchanged.</li>
     * </ul>
     *
     * @return {@code List<PartnerSchemeView>} when {@code network} is present, else
     *         {@link ResolveResponse}.
     */
    @GetMapping
    public Object resolve(
            @RequestParam(value = "network", required = false) String network,
            @RequestParam("country") String country,
            @RequestParam("mode") String mode,
            @RequestParam("direction") String direction) {
        PaymentMode paymentMode = parseMode(mode);
        LocationSchemeQuery query = new LocationSchemeQuery(country, paymentMode, direction);
        if (network != null && !network.isBlank()) {
            List<PartnerSchemeView> candidates = resolver.resolveCandidates(network, query);
            return candidates;
        }
        return ResolveResponse.from(resolver.resolve(query));
    }

    private static PaymentMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "mode (CPM/MPM) required");
        }
        try {
            return PaymentMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "unknown payment mode: " + mode + " (expected CPM or MPM)");
        }
    }
}
