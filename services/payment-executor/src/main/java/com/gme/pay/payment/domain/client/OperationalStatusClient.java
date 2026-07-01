package com.gme.pay.payment.domain.client;

import com.gme.pay.contracts.OperationalStatusView;

/**
 * Reads the platform's current operational / kill-switch state from config-registry's ops read model
 * ({@code GET {config-registry}/v1/ops/operational-status} → {@link OperationalStatusView}).
 *
 * <p>Consumed by the {@code OperationalGate} at the START of NEW payment authorization (the wallet
 * {@code /v1/pay} path and the orchestrated {@code /v1/payments/authorize}) to refuse new work while
 * the platform is paused / in maintenance, or when the resolved partner / scheme / route is
 * suspended. In-flight (already-authorized) payments — confirm/capture, refund, status lookups — are
 * never gated.
 *
 * <p>Backed by {@code RestOperationalStatusClient} (a short-TTL-cached REST client) when
 * {@code gmepay.config-registry.base-url} is configured; otherwise the in-process
 * {@code FixtureOperationalStatusClient} returns {@link OperationalStatusView#allClear()} so tests and
 * a no-config-registry sandbox proceed.
 */
public interface OperationalStatusClient {

    /**
     * @return the current operational status; never {@code null}. Implementations that cannot reach
     *         config-registry apply the configured fail-open / fail-closed policy rather than throwing.
     */
    OperationalStatusView currentStatus();
}
