package com.gme.pay.router.web;

import com.gme.pay.router.resolve.LocationSchemeQuery;
import com.gme.pay.router.resolve.LocationSchemeResolver;
import com.gme.pay.router.resolve.PaymentMode;
import com.gme.pay.router.resolve.ResolutionError;
import com.gme.pay.router.resolve.SchemeResolution;
import com.gme.pay.router.resolve.SchemeResolutionException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/route/resolve?country=KR&mode=MPM&direction=INBOUND} — the
 * data-driven scheme-for-location endpoint qr-service consumes in place of its
 * config-driven country allow-list. Emits each {@link ResolutionError} branch
 * as the matching HTTP status + a stable {@code code} string.
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

    /** Error body mirroring the canonical API-05 {@code {code,message}} shape. */
    public record ResolveError(String code, String message) {
    }

    @GetMapping
    public ResolveResponse resolve(
            @RequestParam("country") String country,
            @RequestParam("mode") String mode,
            @RequestParam("direction") String direction) {
        PaymentMode paymentMode = parseMode(mode);
        SchemeResolution resolution =
                resolver.resolve(new LocationSchemeQuery(country, paymentMode, direction));
        return ResolveResponse.from(resolution);
    }

    private static PaymentMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new SchemeResolutionException(ResolutionError.VALIDATION_ERROR,
                    "mode (CPM/MPM) required");
        }
        try {
            return PaymentMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new SchemeResolutionException(ResolutionError.VALIDATION_ERROR,
                    "unknown payment mode: " + mode + " (expected CPM or MPM)");
        }
    }

    @ExceptionHandler(SchemeResolutionException.class)
    public ResponseEntity<ResolveError> onResolutionError(SchemeResolutionException ex) {
        ResolutionError err = ex.error();
        return ResponseEntity.status(err.httpStatus())
                .body(new ResolveError(err.wireCode(), ex.getMessage()));
    }
}
