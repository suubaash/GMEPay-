package com.gme.pay.reporting.web;

import com.gme.pay.reporting.dto.ReportRequest;
import com.gme.pay.reporting.dto.ReportResponse;
import com.gme.pay.reporting.dto.ReportType;
import com.gme.pay.reporting.service.BokReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * GET /v1/reports — returns BOK FX1014/FX1015 records for the requested period.
 *
 * <p>This service exposes reports derived from committed transactions fetched from
 * the transaction-mgmt service via {@link com.gme.pay.reporting.service.TransactionClient}.
 * It never reads the transaction-mgmt database directly.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code from} (required) – ISO-8601 date, inclusive start of period.</li>
 *   <li>{@code to} (required) – ISO-8601 date, inclusive end of period.</li>
 *   <li>{@code reportType} (optional, default BOK_FX_ALL) –
 *       one of {@code BOK_FX1014}, {@code BOK_FX1015}, {@code BOK_FX_ALL}.</li>
 *   <li>{@code partnerId} (optional) – filter to a single partner.</li>
 * </ul>
 *
 * <p>Returns HTTP 200 with a {@link ReportResponse} JSON body.
 * Returns HTTP 400 if required parameters are missing or invalid.
 */
@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final BokReportService reportService;

    public ReportController(BokReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public ResponseEntity<ReportResponse> getReports(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "BOK_FX_ALL") ReportType reportType,
            @RequestParam(required = false) Long partnerId) {

        ReportRequest request = new ReportRequest();
        request.setFrom(from);
        request.setTo(to);
        request.setReportType(reportType);
        request.setPartnerId(partnerId);

        ReportResponse response = reportService.buildReport(request);
        return ResponseEntity.ok(response);
    }
}
