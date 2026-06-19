package com.gme.pay.rbac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission a handler requires. Place on a controller method (or class, to
 * cover all its methods) and the {@link RbacPermissionInterceptor} enforces it at the API
 * level: the caller's {@link PermissionContext} must hold {@link #value()} (or the
 * {@code "*"} super-grant), else the request is denied (ENFORCE) or logged (AUDIT).
 *
 * <p>No hardcoded authorization logic in code — the permission <em>code</em> is the only
 * literal here; what grants it is entirely DB-driven (roles → permissions).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** The required permission code, e.g. {@code "settlement.resolve_exception"}. */
    String value();
}
