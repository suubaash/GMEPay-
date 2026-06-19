package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.ReportRun;
import java.time.LocalDate;
import java.util.List;

/**
 * BFF adapter onto the reporting-compliance service ({@code GET /v1/reports}).
 *
 * <p>Two implementations, mutually exclusive via {@code gmepay.reporting-compliance.client}:
 * {@link com.gme.pay.bff.client.rest.RestReportingClient} (live HTTP, {@code =rest}) and
 * {@link com.gme.pay.bff.client.stub.StubReportingClient} (in-memory, default) so the BFF
 * boots standalone for local dev / tests.
 *
 * <p>Source-of-truth note: reporting-compliance only exposes BOK FX1014/FX1015 records as a
 * read query. The other UI report types (Hometax, KoFIU, ZeroPay settlement) are
 * scheduler-driven outputs with no query endpoint yet, so the rest client surfaces only the
 * BOK runs it can truthfully source rather than fabricating the others.
 */
public interface ReportingClient {

    /**
     * List report runs for the period. {@code type} optionally narrows to a single report
     * type; {@code from}/{@code to} are inclusive ISO dates (already defaulted by the caller).
     */
    List<ReportRun> listReports(String type, LocalDate from, LocalDate to);

    /** Render the records of one report type+range as a CSV byte payload for download. */
    byte[] downloadCsv(String reportType, LocalDate from, LocalDate to);
}
