package com.gme.pay.reporting.dto;

import java.time.LocalDate;

/**
 * Query parameters for GET /v1/reports.
 * Bound from HTTP request parameters by the controller.
 */
public class ReportRequest {

    /** Inclusive start date (ISO-8601). Required. */
    private LocalDate from;

    /** Inclusive end date (ISO-8601). Required. */
    private LocalDate to;

    /** Which report type to retrieve. Defaults to BOK_FX_ALL. */
    private ReportType reportType = ReportType.BOK_FX_ALL;

    /** Optional partner filter. Null = all partners. */
    private Long partnerId;

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }

    public ReportType getReportType() { return reportType; }
    public void setReportType(ReportType reportType) { this.reportType = reportType; }

    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long partnerId) { this.partnerId = partnerId; }
}
