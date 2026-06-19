package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ReportingClient;
import com.gme.pay.bff.web.dto.ReportRun;
import com.gme.pay.rbac.RequiresPermission;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin Reports centre BFF endpoints (admin-ui /reports page → reporting-compliance).
 *
 * <ul>
 *   <li>{@code GET  /v1/admin/reports?type=&from=&to=} — list report runs ({@link ReportRun}[])</li>
 *   <li>{@code POST /v1/admin/reports/{type}/generate} — (re)compute a run for a type/period</li>
 *   <li>{@code GET  /v1/admin/reports/{id}/download}    — stream the run's records as CSV</li>
 * </ul>
 *
 * <p>Reports are derived on-read from committed transactions by reporting-compliance, so
 * "generate" recomputes rather than enqueueing a job. Only BOK FX1014/FX1015 are sourced
 * today (see {@link ReportingClient}); other types surface once a query backend exists.
 */
@RestController
@RequestMapping("/v1/admin/reports")
public class ReportAdminController {

    private static final int DEFAULT_WINDOW_DAYS = 90;

    private final ReportingClient reporting;

    public ReportAdminController(ReportingClient reporting) {
        this.reporting = reporting;
    }

    @GetMapping
    public List<ReportRun> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate toDate = parse(to, LocalDate.now(), true);
        LocalDate fromDate = parse(from, toDate.minusDays(DEFAULT_WINDOW_DAYS), false);
        return reporting.listReports(emptyToNull(type), fromDate, toDate);
    }

    @PostMapping("/{type}/generate")
    @RequiresPermission("report.generate")   // enforced at the API level when gmepay.rbac.enabled=true
    public ResponseEntity<ReportRun> generate(
            @PathVariable String type,
            @RequestBody(required = false) Map<String, String> body) {
        LocalDate[] range = rangeFromPeriod(body == null ? null : body.get("period"));
        List<ReportRun> runs = reporting.listReports(type, range[0], range[1]);
        return runs.stream().filter(r -> r.type().equals(type)).findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().build()); // UI synthesizes PENDING
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        String[] parts = id.split("~");
        if (parts.length != 3) {
            return ResponseEntity.badRequest().build();
        }
        String type = parts[0];
        LocalDate from = parse(parts[1], LocalDate.now().minusDays(DEFAULT_WINDOW_DAYS), false);
        LocalDate to = parse(parts[2], LocalDate.now(), true);
        byte[] csv = reporting.downloadCsv(type, from, to);
        String filename = type + "_" + from + "_" + to + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    // ---- helpers ----

    /** Parse yyyy-MM-dd or yyyy-MM (→ first/last day) or blank (→ dflt). */
    private static LocalDate parse(String s, LocalDate dflt, boolean endOfMonth) {
        if (s == null || s.isBlank()) return dflt;
        try {
            if (s.length() == 7) { // yyyy-MM
                LocalDate first = LocalDate.parse(s + "-01");
                return endOfMonth ? first.withDayOfMonth(first.lengthOfMonth()) : first;
            }
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    /** Period like "2025-06-01/2025-06-30" or "2025-06" or null → a [from,to] range. */
    private static LocalDate[] rangeFromPeriod(String period) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(DEFAULT_WINDOW_DAYS);
        if (period == null || period.isBlank()) return new LocalDate[]{from, to};
        if (period.contains("/")) {
            String[] p = period.split("/", 2);
            return new LocalDate[]{parse(p[0], from, false), parse(p[1], to, true)};
        }
        return new LocalDate[]{parse(period, from, false), parse(period, to, true)};
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
