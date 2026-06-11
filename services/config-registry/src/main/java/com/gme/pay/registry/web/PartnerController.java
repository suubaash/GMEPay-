package com.gme.pay.registry.web;

import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.registry.partner.PartnerStore;
import com.gme.pay.registry.persistence.PartnerRepository;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read/update partners from the registry, including the per-partner settlement rounding mode. */
@RestController
@RequestMapping("/v1/partners")
public class PartnerController {

    private final PartnerStore store;
    private final PartnerRepository repository;

    public PartnerController(PartnerStore store, PartnerRepository repository) {
        this.store = store;
        this.repository = repository;
    }

    /** Every partner currently in the registry. Powers the Admin UI partner list. */
    @GetMapping
    public List<Partner> list() {
        return store.listAll();
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

    /**
     * Create a partner from the Admin UI form. Body carries the textual {@link PartnerType}
     * and {@link RoundingMode} names (e.g. {@code "LOCAL"}, {@code "HALF_UP"}). Returns 201
     * with the persisted view.
     */
    @PostMapping
    public ResponseEntity<Partner> create(@RequestBody PartnerCreateRequest req) {
        if (req == null || req.partnerId() == null || req.partnerId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "partnerId is required");
        }
        if (req.type() == null || req.type().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "type is required");
        }
        // POST is create-only; duplicate IDs return 409 so the caller (Admin UI) can
        // surface the conflict instead of silently overwriting via PartnerStore.save's
        // upsert semantics.
        if (repository.existsById(req.partnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "partner '" + req.partnerId() + "' already exists");
        }
        PartnerType type = parsePartnerType(req.type());
        RoundingMode mode = req.settlementRoundingMode() == null || req.settlementRoundingMode().isBlank()
                ? RoundingMode.HALF_UP
                : parseRoundingMode(req.settlementRoundingMode());
        Partner saved = store.save(new Partner(req.partnerId(), type, req.settlementCurrency(), mode));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}/rounding-mode")
    public Partner setRoundingMode(@PathVariable String id, @RequestBody RoundingModeRequest request) {
        return store.updateRoundingMode(id, parseRoundingMode(request.mode()));
    }

    private static Instant parseInstant(String at) {
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "'at' must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z), was: " + at);
        }
    }

    private static PartnerType parsePartnerType(String raw) {
        try {
            return PartnerType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "type must be one of LOCAL|OVERSEAS, was: " + raw);
        }
    }

    private static RoundingMode parseRoundingMode(String raw) {
        try {
            return RoundingMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "rounding mode must be a java.math.RoundingMode name (e.g. HALF_UP, DOWN), was: " + raw);
        }
    }

    /** Body for {@link #create}. Mirrors the Admin UI partner form fields. */
    public record PartnerCreateRequest(
            String partnerId,
            String type,
            String settlementCurrency,
            String settlementRoundingMode) {}

    /** Body for {@link #setRoundingMode}; carries the {@link RoundingMode} as its enum name. */
    public record RoundingModeRequest(String mode) {}
}
