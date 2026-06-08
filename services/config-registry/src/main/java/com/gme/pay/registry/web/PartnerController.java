package com.gme.pay.registry.web;

import com.gme.pay.domain.Partner;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Partner config API. Other services read settlement_rounding_mode here (never the DB). */
@RestController
@RequestMapping("/v1/partners")
public class PartnerController {

    private final PartnerStore store;

    public PartnerController(PartnerStore store) {
        this.store = store;
    }

    @GetMapping("/{id}")
    public Partner get(@PathVariable String id) {
        return store.get(id);
    }

    @PutMapping("/{id}/rounding-mode")
    public Partner setRoundingMode(@PathVariable String id, @RequestBody RoundingModeRequest request) {
        return store.updateRoundingMode(id, RoundingMode.valueOf(request.mode()));
    }

    public record RoundingModeRequest(String mode) {
    }
}
