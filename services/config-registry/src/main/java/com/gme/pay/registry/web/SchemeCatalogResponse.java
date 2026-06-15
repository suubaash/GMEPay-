package com.gme.pay.registry.web;

/**
 * Wire DTO for {@code GET /v1/schemes} — one row of the supported-scheme catalog.
 *
 * <p>This is the platform's master roster of QR payment schemes (which networks
 * GMEPay+ can route to), distinct from per-partner scheme <em>enablements</em>
 * (those live in {@code partner_scheme}, V022). It populates the Admin UI schemes
 * page and the Slice-7 scheme-enablement picker.
 *
 * <p>Field names match the BFF's {@code ConfigRegistryClient.SchemeSummary} so the
 * JSON round-trips by field name without an adapter. {@code status} carries the
 * integration truth: {@code ACTIVE} = a live scheme adapter exists; {@code PLANNED}
 * = on the roadmap, no adapter yet (Phase-2). {@code mode} is the target
 * environment ({@code LIVE}/{@code SANDBOX}).
 */
public record SchemeCatalogResponse(
        String schemeId,
        String name,
        String country,
        String currency,
        String mode,
        String status
) {}
