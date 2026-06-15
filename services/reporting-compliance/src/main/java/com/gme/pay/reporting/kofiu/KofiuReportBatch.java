package com.gme.pay.reporting.kofiu;

import java.time.LocalDate;
import java.util.List;

/**
 * Container for the CTR and STR reports produced for a single KST calendar day.
 *
 * <p>Returned by {@link KofiuReportService#buildDailyBatch(LocalDate)} and passed
 * to {@link KofiuFeedFileBuilder} and {@link KofiuFeedClient}.
 */
public final class KofiuReportBatch {

    private final LocalDate reportDate;
    private final List<CtrReport> ctrReports;
    private final List<StrReport> strReports;

    public KofiuReportBatch(
            LocalDate reportDate,
            List<CtrReport> ctrReports,
            List<StrReport> strReports) {
        this.reportDate = reportDate;
        this.ctrReports = List.copyOf(ctrReports);
        this.strReports = List.copyOf(strReports);
    }

    public LocalDate getReportDate() { return reportDate; }
    public List<CtrReport> getCtrReports() { return ctrReports; }
    public List<StrReport> getStrReports() { return strReports; }

    public int totalReports() {
        return ctrReports.size() + strReports.size();
    }

    public boolean isEmpty() {
        return ctrReports.isEmpty() && strReports.isEmpty();
    }
}
