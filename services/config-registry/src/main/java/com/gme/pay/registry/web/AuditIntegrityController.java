package com.gme.pay.registry.web;

import com.gme.pay.registry.audit.AuditIntegrityService;
import com.gme.pay.registry.kyb.KybReclassificationProvenance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator/auditor-facing integrity check over the audit trail (gap T5-1).
 *
 * <pre>
 * GET /v1/audit/integrity                                  full sweep
 * GET /v1/audit/integrity?aggregateType=partner&amp;aggregateId=P001   one chain
 * GET /v1/audit/integrity/kyb-reclassification             V042 drift explanation
 * </pre>
 *
 * <p>A sweep answers "is the log intact, and if not, which row broke first and how" — the
 * question the previous per-row {@code chainValid} flag on {@code GET /v1/audit} could not
 * answer. The response also carries the two honesty counters the sweep computes
 * ({@code legacyV1Rows}, {@code unattributableRows}); see
 * {@link AuditIntegrityService} for what each does and does not assert.
 *
 * <p><b>Read-only by construction</b> — there is no POST here. Verification never writes,
 * including never "repairing" a chain: a mechanism that can re-seal rows on request is a
 * mechanism an attacker can use to launder an edit.
 *
 * <p><b>Security.</b> Like {@code AuditLogController} and {@code OpsControlController}, this
 * service endpoint is reached through the BFF, which applies the operator gate at the edge
 * (config-registry carries no in-process RBAC). Nothing sensitive leaves here beyond row ids
 * and counts — no hashes, no before/after payloads — so an exposure would leak the
 * <i>shape</i> of the log, not its contents. That is a deliberate bound, not an accident:
 * see the "What is NOT exposed" note on {@code AuditLogController}.
 */
@RestController
@RequestMapping("/v1/audit/integrity")
public class AuditIntegrityController {

    private final AuditIntegrityService integrity;
    private final KybReclassificationProvenance kybProvenance;

    public AuditIntegrityController(AuditIntegrityService integrity,
                                    KybReclassificationProvenance kybProvenance) {
        this.integrity = integrity;
        this.kybProvenance = kybProvenance;
    }

    /**
     * Full sweep, or a single chain when both query parameters are supplied.
     *
     * <p>Returning two different response shapes from one path would be poor API design, so a
     * single-chain request is wrapped in the same report envelope with {@code chainsChecked=1}.
     */
    @GetMapping
    public AuditIntegrityService.AuditIntegrityReport verify(
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId) {
        if (aggregateType != null && !aggregateType.isBlank()
                && aggregateId != null && !aggregateId.isBlank()) {
            AuditIntegrityService.ChainResult one = integrity.verifyOne(aggregateType, aggregateId);
            return new AuditIntegrityService.AuditIntegrityReport(
                    one.intact(),
                    1,
                    one.rows(),
                    one.legacyV1Rows(),
                    0, // not computed for a single-chain request; the sweep is the source for it
                    one.intact() ? java.util.List.of() : java.util.List.of(one),
                    java.util.List.of(one));
        }
        return integrity.verifyAll();
    }

    /**
     * The V042 KYB reclassification drift report — why {@code partner_kyb} rows differ from
     * their last sealed audit snapshot, and whether each difference is explained on the record.
     * See {@link KybReclassificationProvenance} for why this exists.
     */
    @GetMapping("/kyb-reclassification")
    public KybReclassificationProvenance.DriftReport kybReclassification() {
        return kybProvenance.driftReport();
    }
}
