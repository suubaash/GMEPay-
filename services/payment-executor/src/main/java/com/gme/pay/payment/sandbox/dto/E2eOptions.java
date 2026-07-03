package com.gme.pay.payment.sandbox.dto;

import java.util.List;

/**
 * Response body for {@code GET /v1/sandbox/e2e/options} — the choices the sandbox UI offers.
 *
 * <pre>
 * { "countries": [{code,label,currency}], "partners": [{code,label}], "mpmTypes": ["STATIC","DYNAMIC"] }
 * </pre>
 */
public record E2eOptions(
        List<Country> countries,
        List<Partner> partners,
        List<String> mpmTypes
) {
    public record Country(String code, String label, String currency) {
    }

    public record Partner(String code, String label) {
    }
}
