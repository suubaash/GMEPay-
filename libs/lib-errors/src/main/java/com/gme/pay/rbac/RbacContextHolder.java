package com.gme.pay.rbac;

/**
 * Thread-bound holder for the current request's {@link PermissionContext}, set by
 * {@link RbacContextFilter} at the start of request processing and cleared in a finally
 * block. Lets the {@link RbacPermissionInterceptor} (and application code) read the caller's
 * permissions without threading the context through method signatures.
 */
public final class RbacContextHolder {

    private static final ThreadLocal<PermissionContext> CONTEXT = new ThreadLocal<>();

    private RbacContextHolder() {}

    public static void set(PermissionContext ctx) {
        CONTEXT.set(ctx);
    }

    /** The current context, or {@code null} if none was set on this thread. */
    public static PermissionContext current() {
        return CONTEXT.get();
    }

    /** The current context, or {@link PermissionContext#ANONYMOUS} if none. */
    public static PermissionContext currentOrAnonymous() {
        PermissionContext c = CONTEXT.get();
        return c != null ? c : PermissionContext.ANONYMOUS;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
