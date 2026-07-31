package com.gme.pay.prefunding.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only integrity report over prefunding's {@code audit_log}.
 *
 * <ul>
 *   <li>{@code GET /internal/v1/audit/chains} — sweep every chain in the table.</li>
 *   <li>{@code GET /internal/v1/audit/chains/one?aggregateType=&aggregateId=} — one chain, e.g.
 *       {@code aggregateType=partner_balance&aggregateId=SENDMN}.</li>
 * </ul>
 *
 * <p>Mounted under {@code /internal/**}, which {@code gmepay.internal-auth.path-patterns} already
 * gates and {@link com.gme.pay.prefunding.config.InternalAuthEnforcedConfig} refuses to let a
 * deployment un-gate. That matters: the report enumerates every partner that has float activity, so
 * it is at least as disclosive as the balance API.
 *
 * <p><b>There is no write operation here and there must never be one.</b> No re-seal, no repair, no
 * backfill, not even a "recompute" that stores its result. The value of the chain is that a stored
 * hash disagreeing with its row content is proof of an edit; an endpoint that could rewrite hashes
 * would convert that proof into an assertion about whoever last called it. A broken chain is an
 * incident, and the response says which row and why so the investigation has somewhere to start.
 */
@RestController
@RequestMapping("/internal/v1/audit")
public class AuditIntegrityController {

    private final AuditChainVerifier verifier;

    public AuditIntegrityController(AuditChainVerifier verifier) {
        this.verifier = verifier;
    }

    /** Verify every chain in the table; broken chains first. */
    @GetMapping("/chains")
    public AuditChainVerifier.SweepReport chains() {
        return verifier.verifyAll();
    }

    /** Verify one {@code (aggregateType, aggregateId)} chain. */
    @GetMapping("/chains/one")
    public AuditChainVerifier.ChainReport chain(@RequestParam String aggregateType,
                                                @RequestParam String aggregateId) {
        return verifier.verify(aggregateType, aggregateId);
    }
}
