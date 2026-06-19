package com.gme.pay.bff.web.dto;

/**
 * Admin-UI wire shape for a report run row (Reports centre, /reports page).
 *
 * <p>Mirrors the {@code ReportRun} contract documented in
 * {@code admin-ui/src/api/reportsApi.js}. {@code recordCount} is carried as a String
 * (BigDecimal-as-string convention) even though it is a count, to match the UI which
 * never {@code Number()}-casts wire amounts. {@code generatedAt} is ISO-8601 UTC.
 *
 * @param id          opaque, download-routable id: {@code "<reportType>~<from>~<to>"}
 * @param type        BOK_FX1014 | BOK_FX1015 | HOMETAX_ETAX | KOFIU_CTR | KOFIU_STR | ZEROPAY_SETTLEMENT
 * @param period      human range, e.g. "2025-06-01..2025-06-30"
 * @param status      PENDING | GENERATED | SUBMITTED | FAILED
 * @param recordCount number of records in the run (string)
 * @param generatedAt ISO-8601 UTC timestamp the report was computed
 * @param downloadUrl BFF passthrough download path, or null
 */
public record ReportRun(
        String id,
        String type,
        String period,
        String status,
        String recordCount,
        String generatedAt,
        String downloadUrl) {}
