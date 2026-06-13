package com.gme.pay.contracts;

import java.time.Instant;

/**
 * Read shape of one {@code partner_ip_allowlist} row (V026) — Slice 8 Lane B.
 * Returned by {@code GET /v1/admin/partners/{code}/ip-allowlist} and by the
 * step-8 PATCH (the fresh set after a bulk replace). The write shape is
 * {@link PartnerIpAllowlistCommand}.
 */
public record PartnerIpAllowlistView(
        Long id,
        String cidr,
        String label,
        String environment,
        Instant createdAt,
        String createdBy) {
}
