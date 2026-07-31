package com.gme.pay.registry.actor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller parameter to the <b>resolved, provenance-tagged</b> identity of the
 * caller — replacing the raw {@code @RequestHeader(value = "X-Actor", required = false)}
 * that every write endpoint in this service used to take (gap T5-1 / CISO §9).
 *
 * <h2>What changed</h2>
 *
 * <p>The wire contract is unchanged: the claimed operator still arrives in the
 * {@code X-Actor} header, so the ops BFF and the admin SPA need no edit. What changed is
 * that the controller no longer receives <i>the header</i>. It receives the output of
 * {@link AuditActorResolver}, which decides — from whether the caller could prove itself —
 * whether that claim may be recorded as an attested principal, must be recorded as an
 * explicit {@code unverified:…} claim, or is absent entirely
 * ({@code AuditActors.UNATTRIBUTED}).
 *
 * <p>Three consequences worth stating, because they are the whole point:
 * <ul>
 *   <li>A parameter annotated with this is <b>never {@code null} and never blank</b>. The
 *       old {@code required = false} header was null on most calls, which is what made
 *       {@code DEFAULT_ACTOR = "system"} necessary in 24 service classes.</li>
 *   <li>It is never the bare literal {@code "system"} — and never {@code "ops"} or
 *       {@code "admin"} either, which two services used as their fallback and which are
 *       worse than {@code "system"} because they name a plausible human role.</li>
 *   <li>A forged header does not become an attributed action. It becomes a row that says,
 *       in the actor column itself, that the name was claimed and not proven.</li>
 * </ul>
 *
 * <p>Why an annotation rather than intercepting {@code @RequestHeader}: Spring registers
 * custom {@link org.springframework.web.method.support.HandlerMethodArgumentResolver}s
 * <i>after</i> the built-in ones, so {@code RequestHeaderMethodArgumentResolver} would
 * always win for an {@code @RequestHeader} parameter and the resolution could be silently
 * bypassed. A distinct annotation cannot be shadowed, and it makes the security property
 * visible at the call site — a reviewer seeing {@code @RequestHeader("X-Actor")} in a new
 * controller now knows it is wrong.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditActorHeader {
}
