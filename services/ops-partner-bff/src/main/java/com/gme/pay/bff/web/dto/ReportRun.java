package com.gme.pay.bff.web.dto;

import java.util.List;

/**
 * Admin-UI wire shape for a report run row (Reports centre, /reports page).
 *
 * <p>Mirrors the {@code ReportRun} contract documented in
 * {@code admin-ui/src/api/reportsApi.js}. {@code recordCount} is carried as a String
 * (BigDecimal-as-string convention) even though it is a count, to match the UI which
 * never {@code Number()}-casts wire amounts. {@code generatedAt} is ISO-8601 UTC.
 *
 * <h2>Filing honesty (GAP T5-2)</h2>
 * <p>{@code status} is the run's <b>filing status as reported by reporting-compliance</b>, mapped
 * through {@link com.gme.pay.bff.compliance.FilingStatuses}. It is not synthesized here: the BFF
 * used to hardcode {@code "GENERATED"} for every row, which discarded the only available truth.
 * A report is a locally generated projection — nothing on this row has been filed with an
 * authority, and {@code filingChannelUnavailableReason} / {@code filingChannels} say why.
 *
 * @param id          opaque, download-routable id: {@code "<reportType>~<from>~<to>"}
 * @param type        BOK_FX1014 | BOK_FX1015 | HOMETAX_ETAX | KOFIU_CTR | KOFIU_STR | ZEROPAY_SETTLEMENT
 * @param period      human range, e.g. "2025-06-01..2025-06-30"
 * @param status      filing status, verbatim from reporting-compliance where honest:
 *                    {@code PENDING | GENERATED | VALIDATED | NOT_FILED_CHANNEL_UNAVAILABLE |
 *                    TRANSMITTED | ACKNOWLEDGED | FAILED}, or {@code UNKNOWN} when the service did
 *                    not report one. Never a synthesized success — the retired
 *                    {@code SUBMITTED}/{@code CONFIRMED}/{@code ACCEPTED} values cannot appear.
 * @param recordCount number of records in the run (string)
 * @param generatedAt ISO-8601 UTC timestamp the report was computed
 * @param downloadUrl BFF passthrough download path, or null
 * @param filingChannelUnavailableReason why this run cannot be filed (or why the status is
 *                    {@code UNKNOWN}); null when the lane has a live channel and the status is
 *                    self-explanatory
 * @param filingChannels per-lane channel availability board; null when the service did not report it
 */
public record ReportRun(
        String id,
        String type,
        String period,
        String status,
        String recordCount,
        String generatedAt,
        String downloadUrl,
        String filingChannelUnavailableReason,
        List<FilingChannelState> filingChannels) {}
