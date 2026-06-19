package com.gme.pay.rbac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sink for {@link RbacDecision}s emitted by the interceptor. A service can supply its own
 * bean to forward decisions to the async audit pipeline (P6 — every allow/deny + reason
 * becomes an audit row); the default {@link Logging} impl just logs, so enforcement works
 * out of the box. Implementations must be non-blocking / fail-open — never break the
 * request because audit failed.
 */
public interface RbacDecisionListener {

    void onDecision(RbacDecision decision);

    /** Default sink: logs allow at DEBUG, deny at INFO. */
    class Logging implements RbacDecisionListener {
        private static final Logger log = LoggerFactory.getLogger("com.gme.pay.rbac.audit");

        @Override
        public void onDecision(RbacDecision d) {
            if (d.allowed()) {
                log.debug("RBAC allow principal={} perm={} {} {}",
                        d.principalId(), d.permission(), d.method(), d.path());
            } else {
                log.info("RBAC deny [{}] principal={} perm={} {} {} reason={}",
                        d.mode(), d.principalId(), d.permission(), d.method(), d.path(), d.reason());
            }
        }
    }
}
