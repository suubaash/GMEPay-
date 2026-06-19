package com.gme.pay.gateway.filter;

import reactor.core.publisher.Mono;

/**
 * Resolves a principal's effective RBAC claims (by username) for the gateway to stamp
 * downstream. Implementations must be fail-open — on any error return {@link Mono#empty()}
 * so the gateway never breaks; the downstream {@code @RequiresPermission} interceptor then
 * denies (no stamped permissions) rather than the edge 500-ing.
 */
public interface RbacClaimResolver {

    Mono<RbacClaims> resolve(String username);
}
