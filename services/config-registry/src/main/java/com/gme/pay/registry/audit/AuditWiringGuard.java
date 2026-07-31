package com.gme.pay.registry.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Makes a missing audit pipeline a <b>startup failure</b> instead of a silent loss of the audit
 * trail (gap T5-1 / CISO §9).
 *
 * <h2>The failure mode this closes</h2>
 *
 * <p>25 of this service's 26 audit call sites are written as:
 *
 * <pre>
 *   AuditLogService auditLog = auditLogProvider.getIfAvailable();
 *   if (auditLog != null) {
 *       auditLog.publish(...);
 *   }
 * </pre>
 *
 * <p>That {@code ObjectProvider} indirection exists for a good reason — the {@code @DataJpaTest}
 * slice tests import a service without the audit module on their {@code @Import} list, and forcing
 * a hard dependency would break a dozen of them for no benefit. But it has a consequence the audit
 * called out: <i>if the bean fails to wire in production, every business write succeeds with zero
 * audit rows and zero errors.</i> A fee change, a credential rotation, a KYB verdict — all applied,
 * none recorded, nothing logged. The platform would look healthy and the trail would be empty, and
 * the first time anyone found out would be when a regulator asked for it.
 *
 * <p>This guard takes {@link AuditLogService} as a <b>hard constructor dependency</b>. In the real
 * application context (component scan on, audit module present) it wires and logs a one-line
 * confirmation. If the audit bean is ever absent — a broken {@code @ComponentScan}, an excluded
 * configuration, a datasource that failed to produce the repository — Spring cannot construct this
 * bean and the service <b>refuses to start</b>.
 *
 * <p>Failing to boot is the correct trade. A config-registry that is up but unaudited is worse than
 * one that is down: the first silently destroys the evidence of every change made while it runs,
 * and the second is a page that someone fixes in ten minutes.
 *
 * <p>Slice tests are unaffected — they never import this class, so the {@code ObjectProvider}
 * fallback keeps working exactly as before wherever it is genuinely appropriate.
 */
@Component
public class AuditWiringGuard {

    private static final Logger log = LoggerFactory.getLogger(AuditWiringGuard.class);

    public AuditWiringGuard(AuditLogService auditLogService) {
        // The parameter is the assertion. Nothing to store: this bean exists so that the
        // container's inability to satisfy it becomes a startup failure.
        log.info("audit: ADR-007 tier-1 pipeline wired ({}) — every audited write path in this "
                        + "service has a publisher to write to",
                auditLogService.getClass().getSimpleName());
    }
}
