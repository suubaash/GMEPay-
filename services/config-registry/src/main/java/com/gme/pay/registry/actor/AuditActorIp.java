package com.gme.pay.registry.actor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller parameter to the client IP resolved by
 * {@link AuditActorResolver#resolveIp}, replacing the raw
 * {@code @RequestHeader("X-Forwarded-For")} that {@code OpsControlController} used.
 *
 * <p>The CISO audit's exact words were that {@code actorIp} "is passed as literal {@code null}
 * at every call site except {@code OpsControlService}, which takes it from the equally
 * client-supplied {@code X-Forwarded-For}". So the one endpoint family that recorded an IP at
 * all recorded a free-text field — an operator pausing the platform could write any IP they
 * liked into the audit row, which is worse than recording none because it looks like evidence.
 *
 * <p>The resolved value prefers the transport-level peer address (not client-supplied) and
 * honours {@code X-Forwarded-For} only from an attested caller with
 * {@code gmepay.audit.actor.trust-forwarded-ip=true}. May be {@code null} when no request
 * context exists. From {@link com.gme.pay.audit.HashChain#CHAIN_V2} on, this value is sealed
 * into the row digest, so it can no longer be rewritten after the fact either.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditActorIp {
}
