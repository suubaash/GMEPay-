package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ReportingClient;
import com.gme.pay.bff.web.dto.ReportRun;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link ReportingClient} so the BFF boots standalone (local dev / tests) without
 * reporting-compliance. Wired by default; {@link com.gme.pay.bff.client.rest.RestReportingClient}
 * takes over when {@code gmepay.reporting-compliance.client=rest}.
 *
 * <p>Returns deterministic BOK FX1014/FX1015 runs over the requested range so the Reports
 * page renders a realistic timeline offline.
 */
@Component
@ConditionalOnProperty(name = "gmepay.reporting-compliance.client", havingValue = "stub", matchIfMissing = true)
public class StubReportingClient implements ReportingClient {

    private static final String GENERATED_AT = "2026-06-01T01:30:00Z";

    @Override
    public List<ReportRun> listReports(String type, LocalDate from, LocalDate to) {
        String period = from + ".." + to;
        List<ReportRun> all = new ArrayList<>();
        all.add(run("BOK_FX1014", period, from, to, "1428"));
        all.add(run("BOK_FX1015", period, from, to, "312"));
        if (type == null || type.isBlank()) {
            return all;
        }
        return all.stream().filter(r -> r.type().equals(type)).toList();
    }

    @Override
    public byte[] downloadCsv(String reportType, LocalDate from, LocalDate to) {
        String csv = "txn_id,txn_ref,report_type,report_date,partner_id,collection_amount,"
                + "collection_ccy,payout_amount,payout_ccy,offer_rate_coll,cross_rate,usd_amount,submission_status\n"
                + "1001,PTXN0001," + reportType + "," + from + ",1,1000000,KRW,73000,NPR,,,750.00,SUBMITTED\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private static ReportRun run(String type, String period, LocalDate from, LocalDate to, String count) {
        String id = type + "~" + from + "~" + to;
        return new ReportRun(id, type, period, "GENERATED", count, GENERATED_AT,
                "/v1/admin/reports/" + id + "/download");
    }
}
