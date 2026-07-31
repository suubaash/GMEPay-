package com.gme.pay.registry.web;

import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.registry.scheme.PartnerSchemeService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/schemes/{schemeId}/operating-hours} — the SERVICE-facing read of the
 * {@code scheme_operating_hours} reference table (V024). Gap <b>T3-6</b>.
 *
 * <h2>Why a second mapping for the same data</h2>
 * The identical projection is already served by {@link PartnerSchemeController} at
 * {@code /v1/admin/schemes/{schemeId}/operating-hours} — the <b>admin wizard</b> surface, sitting behind
 * the BFF's Keycloak OIDC role gate. V024 was written for two consumers ("the router and the settlement
 * calculator need this to decide 'can this transaction route NOW?'"), and neither of those is an
 * operator with an OIDC session: they are in-cluster services on the payment path. Mounting the read at
 * the flat {@code /v1/schemes/...} namespace puts it where every other service-to-service config read
 * already lives ({@code /v1/schemes/{schemeId}/merchant-fees/effective},
 * {@code /v1/commission/effective}, {@code /v1/ops/operational-status}) instead of teaching the payment
 * path to call an {@code /v1/admin} URL.
 *
 * <p>Both mappings delegate to the SAME {@link PartnerSchemeService#operatingHours(String)} method — no
 * duplicated projection, no second roster check, and no possibility of the two answers diverging.
 *
 * <p>Read-only by construction: the table is migration-seeded reference data ({@code @Immutable}
 * entity, no write path anywhere in the service), so there is no command counterpart to gate.
 *
 * <p>Contract: 7 rows for a seeded scheme (Monday(0)..Sunday(6)); an EMPTY list for a rostered scheme
 * whose schedule has not been seeded yet (QRIS / KHQR / NEPAL / SENDMN); 404 for a scheme outside the
 * V022 roster. Callers must treat both the empty list and the 404 as
 * {@code SchemeAvailabilityVerdict.UNVERIFIED} — never as "open".
 */
@RestController
@RequestMapping("/v1/schemes")
public class SchemeOperatingHoursController {

    private final PartnerSchemeService schemeService;

    public SchemeOperatingHoursController(PartnerSchemeService schemeService) {
        this.schemeService = schemeService;
    }

    /**
     * The weekly operating schedule for one scheme — local open/close window, the optional settlement
     * cutoff, and the IANA timezone the three times are evaluated in.
     */
    @GetMapping("/{schemeId}/operating-hours")
    public List<SchemeOperatingHoursView> operatingHours(@PathVariable String schemeId) {
        return schemeService.operatingHours(schemeId);
    }
}
