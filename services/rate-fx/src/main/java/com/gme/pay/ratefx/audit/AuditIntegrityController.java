package com.gme.pay.ratefx.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only integrity report over rate-fx's {@code audit_log}.
 *
 * <ul>
 *   <li>{@code GET /internal/v1/audit/chains} — sweep every chain in the table.</li>
 *   <li>{@code GET /internal/v1/audit/chains/one?aggregateType=rate_snapshot&aggregateId=MNT} — one
 *       currency's pricing history.</li>
 * </ul>
 *
 * <p>Mounted under {@code /internal/**}, which is listed in
 * {@code gmepay.internal-auth.path-patterns} so the report is operator-only: it enumerates every
 * currency GME prices and every operator who has touched a rate. The <b>public</b> partner surface
 * ({@code POST /v1/rates}, {@code /v1/quotes/**}) stays outside the gate as before — adding
 * {@code /internal/**} widens what is protected and narrows nothing.
 *
 * <p><b>There is no write operation here and there must never be one.</b> No re-seal, no repair, no
 * backfill. The chain's value is that a stored hash disagreeing with its row content is proof of an
 * edit; an endpoint that could rewrite hashes would downgrade that proof to an assertion about
 * whoever last called it. A broken chain is an incident, and the response names the row and the reason
 * so the investigation has a starting point.
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
