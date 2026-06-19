package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.pay.bff.client.ReportingClient;
import com.gme.pay.bff.web.dto.ReportRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link ReportingClient}. Calls reporting-compliance {@code GET /v1/reports}
 * (BOK FX1014/FX1015 records for a date range) and folds them into the Admin-UI
 * {@link ReportRun} list shape. Active when {@code gmepay.reporting-compliance.client=rest};
 * otherwise {@link com.gme.pay.bff.client.stub.StubReportingClient} is wired.
 *
 * <p>Built from the Spring-autoconfigured {@code RestClient.Builder} (see {@link ClientBeans})
 * for parity with the other Rest* adapters. Degrades gracefully (empty list) when
 * reporting-compliance is unreachable, so the Reports page shows a real-but-empty state
 * rather than an error.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.reporting-compliance.client", havingValue = "rest")
public class RestReportingClient implements ReportingClient {

    private static final Logger log = LoggerFactory.getLogger(RestReportingClient.class);

    /** BOK report types reporting-compliance can serve as a read query. */
    private static final String FX1014 = "BOK_FX1014";
    private static final String FX1015 = "BOK_FX1015";

    /** Fixed CSV column order, matching reporting-compliance BokFxRecordDto JSON fields. */
    private static final String[] CSV_COLS = {
            "txn_id", "txn_ref", "report_type", "report_date", "partner_id",
            "collection_amount", "collection_ccy", "payout_amount", "payout_ccy",
            "offer_rate_coll", "cross_rate", "usd_amount", "submission_status"};

    private final RestClient restClient;

    @Autowired
    public RestReportingClient(
            RestClient.Builder builder,
            @Value("${gmepay.reporting-compliance.base-url:http://reporting-compliance:8080}") String baseUrl) {
        this(builder.baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestReportingClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<ReportRun> listReports(String type, LocalDate from, LocalDate to) {
        String upstreamType = isBok(type) ? type : "BOK_FX_ALL";
        JsonNode resp = fetch(from, to, upstreamType);
        if (resp == null) {
            return List.of();
        }
        String generatedAt = isoUtc(textOrNull(resp, "generated_at"));
        String period = from + ".." + to;

        Map<String, Integer> counts = new LinkedHashMap<>();
        if (isBok(type)) {
            counts.put(type, 0); // always show the requested BOK type, even at 0 records
        }
        JsonNode records = resp.path("records");
        if (records.isArray()) {
            for (JsonNode rec : records) {
                String rt = normalizeType(textOrNull(rec, "report_type"));
                counts.merge(rt, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().map(e -> {
            String runType = e.getKey();
            String id = runType + "~" + from + "~" + to;
            return new ReportRun(id, runType, period, "GENERATED",
                    String.valueOf(e.getValue()), generatedAt,
                    "/v1/admin/reports/" + id + "/download");
        }).toList();
    }

    @Override
    public byte[] downloadCsv(String reportType, LocalDate from, LocalDate to) {
        String upstreamType = isBok(reportType) ? reportType : "BOK_FX_ALL";
        JsonNode resp = fetch(from, to, upstreamType);
        StringBuilder csv = new StringBuilder(String.join(",", CSV_COLS)).append('\n');
        if (resp != null && resp.path("records").isArray()) {
            for (JsonNode rec : resp.path("records")) {
                StringBuilder row = new StringBuilder();
                for (int i = 0; i < CSV_COLS.length; i++) {
                    if (i > 0) row.append(',');
                    row.append(csvCell(textOrNull(rec, CSV_COLS[i])));
                }
                csv.append(row).append('\n');
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    private JsonNode fetch(LocalDate from, LocalDate to, String reportType) {
        try {
            return restClient.get()
                    .uri(uri -> uri.path("/v1/reports")
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("reportType", reportType)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException network) {
            log.warn("reporting-compliance unreachable on /v1/reports: {}", network.getMessage());
            return null;
        } catch (RuntimeException e) {
            log.warn("reporting-compliance error on /v1/reports: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isBok(String type) {
        return FX1014.equals(type) || FX1015.equals(type);
    }

    private static String normalizeType(String rt) {
        if (rt == null) return "BOK_FX_ALL";
        String u = rt.toUpperCase();
        if (u.contains("1014")) return FX1014;
        if (u.contains("1015")) return FX1015;
        return u;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String isoUtc(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:?\\d\\d$")) return s;
        return s + "Z";
    }

    private static String csvCell(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
