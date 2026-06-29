package com.gme.pay.internalauth;

/**
 * Header contract for service-to-service internal authentication. Some endpoints are
 * internal-only — they exist to be called by other GMEPay+ services across the in-cluster
 * network, never by an external client — but are not themselves behind the api-gateway
 * (e.g. auth-identity's {@code /v1/rbac/**} and {@code /v1/approvals/**}, reached directly
 * by the gateway's claim resolver and the ops BFF). Without a network-policy / mTLS mesh,
 * any actor with network reach could call them. This token is the application-layer gate:
 * a trusted in-cluster caller presents the shared secret; the {@link InternalAuthFilter}
 * rejects anyone who cannot.
 */
public final class InternalAuthHeaders {

    private InternalAuthHeaders() {}

    /** Shared-secret token proving the caller is a trusted in-cluster service (gateway / BFF). */
    public static final String INTERNAL_TOKEN = "X-Gme-Internal";
}
