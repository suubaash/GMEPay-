package com.gme.pay.auth.audit;

import com.gme.pay.auth.audit.AuditChainVerifier.ChainReport;
import com.gme.pay.auth.audit.AuditChainVerifier.SweepReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only tamper-evidence surface for this service's {@code audit_log}.
 *
 * <ul>
 *   <li>{@code GET /internal/auth/audit/chain?aggregateType=&aggregateId=} — verify one chain.</li>
 *   <li>{@code GET /internal/auth/audit/chains} — sweep every chain in the table.</li>
 * </ul>
 *
 * <h2>Perimeter</h2>
 *
 * <p>Mounted under {@code /internal/auth/**}, so it sits behind the same service-to-service
 * internal-auth gate as JWT minting and credential issuance — {@code application.yml} gates
 * {@code /internal/**} and {@code InternalAuthEnforcedConfig} refuses to start if that pattern
 * is ever dropped. A caller must present {@code X-Gme-Internal}.
 *
 * <p>The gate matters more here than the read-only verbs suggest. The response says how many
 * audit rows exist per aggregate and which row ids they occupy, i.e. it is a map of the audit
 * trail — useful to an operator confirming integrity and equally useful to an attacker choosing
 * what to rewrite. And {@code GET /internal/auth/audit/chains} recomputes a SHA-256 over every
 * row in the table, so anonymous access would also be a cheap way to make this service do
 * unbounded work.
 *
 * <h2>Why read-only, with no repair verb</h2>
 *
 * <p>There is deliberately no endpoint that "fixes" or re-seals a chain. Re-hashing existing
 * rows would destroy the only property the chain has: after a re-seal, an honest repair and an
 * attacker who rewrote history and recomputed the hashes are byte-for-byte identical. A broken
 * chain is a finding to be investigated against backups and the tier-2/tier-3 sinks, never
 * something to be made to pass.
 */
@RestController
@RequestMapping("/internal/auth/audit")
public class AuditChainController {

    private final AuditChainVerifier verifier;

    public AuditChainController(AuditChainVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * Verify a single chain. Returns {@code 200} with {@code intact=false} rather than an error
     * status when the chain is broken: this is a report about data, and a broken chain is a
     * successful answer to the question asked. Alerting keys off the body
     * ({@code intact}/{@code firstBrokenRowId}), which is also what a human reading the JSON
     * needs — an HTTP 409 would carry none of the detail.
     */
    @GetMapping("/chain")
    public ChainReport chain(@RequestParam("aggregateType") String aggregateType,
                             @RequestParam("aggregateId") String aggregateId) {
        return verifier.verify(aggregateType, aggregateId);
    }

    /** Sweep every chain in {@code audit_log}; broken chains are listed first. */
    @GetMapping("/chains")
    public SweepReport chains() {
        return verifier.verifyAll();
    }
}
