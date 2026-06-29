package com.gme.pay.registry.web;

import com.gme.pay.contracts.SchemeCommissionShareCommand;
import com.gme.pay.contracts.SchemeCommissionShareView;
import com.gme.pay.registry.scheme.SchemeCommissionShareService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scheme-side commission-share setup — the configurable GME ↔ scheme split of
 * the net merchant fee (V031). Part of "QR scheme setup"; complements the
 * read-only {@link SchemeCatalogController}.
 *
 * <ul>
 *   <li>{@code GET  /v1/schemes/{schemeId}/commission-shares} — current rows.</li>
 *   <li>{@code PUT  /v1/schemes/{schemeId}/commission-shares} — bulk replace
 *       (one row per direction; empty list clears).</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/schemes/{schemeId}/commission-shares")
public class SchemeCommissionShareController {

    private final SchemeCommissionShareService service;

    public SchemeCommissionShareController(SchemeCommissionShareService service) {
        this.service = service;
    }

    /** Current commission-share rows for the scheme. 404 when the code is unknown. */
    @GetMapping
    public List<SchemeCommissionShareView> get(@PathVariable String schemeId) {
        return service.currentCommissionShares(schemeId);
    }

    /**
     * Bulk-replace the scheme's commission-share set. 404 unknown scheme; 400 on
     * validation failure (bad direction / out-of-range share / duplicate direction).
     */
    @PutMapping
    public List<SchemeCommissionShareView> replace(
            @PathVariable String schemeId,
            @RequestBody List<SchemeCommissionShareCommand> shares,
            @RequestHeader(name = "X-Actor", required = false) String actor) {
        return service.replaceCommissionShares(schemeId, shares, actor);
    }
}
