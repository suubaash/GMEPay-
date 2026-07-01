package com.gme.pay.registry.web;

import com.gme.pay.contracts.KybCommand;
import com.gme.pay.contracts.KybView;
import com.gme.pay.registry.kyb.KybService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 3 — KYB endpoints on the partner resource (wizard step 3 + screening).
 * Kept in its own controller (rather than growing {@code PartnerController})
 * so each slice's surface stays reviewable in isolation; both mount under
 * {@code /v1/partners} and share the partner-code-on-the-URL-line contract.
 *
 * <p>{@code {partnerCode}} / {@code {id}} is always the human-facing business
 * code (e.g. {@code "GMEREMIT"}), never the BIGINT surrogate — same URL
 * contract as every other partner endpoint.
 */
@RestController
@RequestMapping("/v1/partners")
public class PartnerKybController {

    private final KybService kybService;

    public PartnerKybController(KybService kybService) {
        this.kybService = kybService;
    }

    /**
     * Save Step-3 (KYB) onto an existing draft — full-state replace of the
     * operator-editable fields ({@link KybService#upsertStep3}): the current
     * {@code partner_kyb} row is superseded and a fresh one inserted in one
     * transaction (SCD-6, ADR-010) with one {@code partner_kyb} audit row
     * (ADR-007). Screening fields are carried forward server-side.
     *
     * <p>Returns 200 with the fresh {@link KybView}; 404 unknown draft; 409
     * when the partner has left ONBOARDING; 400 on validation failure
     * (message carries the offending {@code uboList[i]} index where
     * applicable).
     */
    @PatchMapping("/draft/{partnerCode}/step-3")
    public KybView patchDraftStep3(@PathVariable String partnerCode,
                                   @RequestBody KybCommand.UpdateStep3 req,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        return kybService.upsertStep3(partnerCode, req, actor);
    }

    /**
     * The CURRENT KYB view for {@code id} (the partner business code) —
     * powers the wizard's step-3 rehydrate and the partner detail page's
     * screening panel. 404 when the code is unknown or no KYB row exists yet.
     */
    @GetMapping("/{id}/kyb")
    public KybView getKyb(@PathVariable String id) {
        return kybService.currentKyb(id);
    }

    /**
     * Run sanctions screening for {@code id} via the kyb-adapter seam
     * (ADR-009) and store the verdict on a fresh SCD-6 row. No request body:
     * the server assembles the screening subject from the stored aggregate.
     * Not gated on ONBOARDING (rescreens happen for LIVE partners too).
     *
     * <p>Returns 200 with the updated {@link KybView}; 404 unknown partner;
     * 502 when the kyb-adapter service is unreachable (REST client mode).
     */
    @PostMapping("/{id}/kyb/screen")
    public KybView runScreening(@PathVariable String id,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return kybService.runScreening(id, actor);
    }

    /**
     * Wave-3 — run a FULL KYB verification for {@code id} via the kyb-adapter
     * verify seam ({@code POST /v1/kyb/verify}, ADR-009) and store the collapsed
     * decision (+ provider ref + screening status) on a fresh SCD-6 row. The
     * document-aware counterpart to {@code /screen}: the optional body lists the
     * onboarding documents the operator attached at the wizard's KYB step so the
     * adapter can downgrade a clean run to MANUAL_REVIEW on a missing document.
     *
     * <p>Returns 200 with the updated {@link KybView}; 404 unknown partner; the
     * adapter's 4xx and a 502-on-unreachable (REST mode) pass through.
     */
    @PostMapping("/{id}/kyb/verify")
    public KybView runVerification(@PathVariable String id,
                                   @RequestBody(required = false) VerifyRequest req,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        List<String> docs = req == null ? null : req.suppliedDocuments();
        boolean force = req != null && Boolean.TRUE.equals(req.force());
        return kybService.runVerification(id, docs, force, actor);
    }

    /**
     * Request body for {@code POST /{id}/kyb/verify} — both fields optional. An
     * absent body verifies with no documents and {@code force=false} (idempotent
     * replay of any prior run).
     *
     * @param suppliedDocuments onboarding document types the operator attached.
     * @param force             re-run even if a prior run for the same subject exists.
     */
    public record VerifyRequest(List<String> suppliedDocuments, Boolean force) {
    }
}
