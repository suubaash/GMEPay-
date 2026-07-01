package com.gme.pay.settlement.rerun;

import com.gme.pay.settlement.recon.BatchNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator recon re-run API.
 *
 * <pre>
 * POST /v1/settlements/recon/rerun   body { batchId | settlementDate, operatorId, reason }
 * </pre>
 *
 * <p>Re-runs reconciliation for one batch or a whole business date, reusing the idempotent diff engine
 * (no double-post / duplicate exception lines), records who/why in the audit log, emits a RECON_BREAK
 * ops alert on any break, and returns the resulting match/exception summary. Money amounts in the
 * response are counts; per-line money rides in the emitted alert per MONEY_CONVENTION.md.
 */
@RestController
@RequestMapping("/v1/settlements/recon")
public class ReconRerunController {

    private final ReconRerunService service;

    public ReconRerunController(ReconRerunService service) {
        this.service = service;
    }

    @PostMapping("/rerun")
    public ResponseEntity<ReconRerunResponse> rerun(@RequestBody ReconRerunRequest request) {
        try {
            return ResponseEntity.ok(service.rerun(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (BatchNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
