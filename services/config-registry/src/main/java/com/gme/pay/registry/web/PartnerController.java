package com.gme.pay.registry.web;

import com.gme.pay.domain.Partner;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read/update partners from the registry, including the per-partner settlement rounding mode. */
@RestController
@RequestMapping("/v1/partners")
public class PartnerController {

    private final PartnerStore store;

    public PartnerController(PartnerStore store) {
        this.store = store;
    }

    /**
     * Current view by default (served cache-aside). With {@code ?at=<ISO-8601 instant>}
     * the lookup is point-in-time against the half-open effective window
     * {@code [effective_from, effective_to)}; point-in-time reads bypass the cache.
     */
    @GetMapping("/{id}")
    public Partner get(@PathVariable String id,
                       @RequestParam(name = "at", required = false) String at) {
        if (at == null || at.isBlank()) {
            return store.get(id);
        }
        return store.getEffectiveAt(id, parseInstant(at));
    }

    @PutMapping("/{id}/rounding-mode")
    public Partner setRoundingMode(@PathVariable String id, @RequestBody RoundingModeRequest request) {
        return store.updateRoundingMode(id, RoundingMode.valueOf(request.mode()));
    }

    private static Instant parseInstant(String at) {
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'at' must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z), was: " + at);
        }
    }

    /** Body for {@link #setRoundingMode}; carries the {@link RoundingMode} as its enum name. */
    public record RoundingModeRequest(String mode) {}
}
