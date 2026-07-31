package com.gme.pay.reporting.hometax;

import com.gme.pay.reporting.persistence.ReportFiling;
import com.gme.pay.reporting.persistence.ReportFilingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Monthly scheduler for UC-04-04 Hometax e-tax-invoice submission.
 *
 * <p>Fires at 02:00 KST on the last day of every month
 * ({@code 0 0 2 L * ?} — last day of month, cron expression).
 * The {@code @EnableScheduling} annotation is owned by Lane A's
 * {@code ReportingComplianceApplication}; this bean simply declares its
 * schedule and will execute once Lane A's annotation activates the framework.
 *
 * <h2>Gate</h2>
 * Controlled by {@code gmepay.reporting.hometax.enabled} (default {@code false}).
 * When {@code false} the job logs a skip and returns immediately — no work is
 * done, no network calls are made.
 *
 * <h2>Config keys</h2>
 * <ul>
 *   <li>{@code gmepay.reporting.hometax.enabled} — master on/off gate</li>
 *   <li>{@code gmepay.reporting.hometax.partner-code} — merchant business code
 *       used to look up regulatory config (vat_treatment, cert id)</li>
 *   <li>{@code gmepay.reporting.hometax.fee-rate} — decimal fee rate, e.g.
 *       {@code 0.0150}</li>
 *   <li>{@code gmepay.hometax.cert-id} — lib-vault document id of the NTS
 *       mTLS issuer certificate</li>
 * </ul>
 *
 * <h2>Spring 6 / Java 21 constructor note</h2>
 * {@code @Autowired} is declared on the constructor explicitly (defensive style).
 * Tests may instantiate this class directly — {@code @Value} is just metadata
 * and does not prevent direct Java construction.
 */
@Component
public class HometaxInvoiceScheduler {

    private static final Logger log = LoggerFactory.getLogger(HometaxInvoiceScheduler.class);

    /** KST = UTC+9. All schedule logic uses KST. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HometaxInvoiceService invoiceService;
    private final boolean enabled;
    private final String partnerCode;
    private final BigDecimal feeRate;
    private final String certId;

    /** Owned-datastore filing register; null when running without a DB (unit tests). */
    @Nullable
    private final ReportFilingService filingService;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Component} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public HometaxInvoiceScheduler(
            HometaxInvoiceService invoiceService,
            ReportFilingService filingService,
            @Value("${gmepay.reporting.hometax.enabled:false}") boolean enabled,
            @Value("${gmepay.reporting.hometax.partner-code:GMEREMIT}") String partnerCode,
            @Value("${gmepay.reporting.hometax.fee-rate:0.0150}") BigDecimal feeRate,
            @Value("${gmepay.hometax.cert-id:stub-cert-id}") String certId) {
        this.invoiceService = invoiceService;
        this.filingService = filingService;
        this.enabled = enabled;
        this.partnerCode = partnerCode;
        this.feeRate = feeRate;
        this.certId = certId;
    }

    /**
     * Constructor for unit tests that exercise only aggregation/submission behaviour
     * (no filing register). {@code @Value} metadata is not enforced at instantiation.
     */
    public HometaxInvoiceScheduler(
            HometaxInvoiceService invoiceService,
            boolean enabled,
            String partnerCode,
            BigDecimal feeRate,
            String certId) {
        this(invoiceService, null, enabled, partnerCode, feeRate, certId);
    }

    /**
     * Monthly Hometax invoice job.
     *
     * <p>Cron: {@code 0 0 2 L * ?} — 02:00 on the last day of every month.
     * Spring's cron interpreter resolves {@code L} in the day-of-month
     * position to the last calendar day. The zone is KST (Asia/Seoul) so
     * the trigger aligns with the Korean fiscal calendar.
     */
    @Scheduled(cron = "0 0 2 L * ?", zone = "Asia/Seoul")
    public void runMonthlyInvoice() {
        if (!enabled) {
            log.debug("Hometax invoice job is disabled (gmepay.reporting.hometax.enabled=false). Skipping.");
            return;
        }

        // The job runs on the last day of month; the billing period is the
        // current month in KST.
        YearMonth period = YearMonth.from(LocalDate.now(KST));
        log.info("Hometax monthly invoice job starting for period={} partnerCode={}", period, partnerCode);

        try {
            HometaxInvoiceResponse response = invoiceService.submitInvoicesForPeriod(
                    period, feeRate, partnerCode, certId);

            if (response.isFiled()) {
                log.info("Hometax invoice FILED with NTS: period={} invoiceId={} "
                                + "ntsConfirmation={} status={}",
                        period, response.getInvoiceId(), response.getNtsConfirmation(),
                        response.getStatus());
            } else {
                // Normal path today: the invoice was aggregated but no NTS channel exists.
                log.warn("Hometax invoice AGGREGATED BUT NOT FILED: period={} status={} reason={}",
                        period, response.getStatus(), response.getChannelUnavailableReason());
            }
            recordFiling(period, response);
        } catch (Exception ex) {
            log.error("Hometax monthly invoice job failed for period={}: {}", period, ex.getMessage(), ex);
            // Do not rethrow — scheduler must survive individual-run failures
        }
    }

    /**
     * Writes the HOMETAX lane's row into the {@code report_filing} register so the register
     * reflects reality: GENERATED for the aggregation that really happened, then settled
     * against the channel — {@code NOT_FILED_CHANNEL_UNAVAILABLE} while OI-02 is open.
     * Best-effort: a persistence failure must not abort the run.
     */
    private void recordFiling(YearMonth period, HometaxInvoiceResponse response) {
        if (filingService == null) {
            return;
        }
        try {
            ReportFiling filing = filingService.openFiling(
                    ReportFiling.Lane.HOMETAX, "ETAX", period.atEndOfMonth());
            filingService.recordGenerated(filing.getId(), 1, null);
            if (response.isFiled()) {
                filingService.recordTransmission(filing.getId(), response.getNtsConfirmation());
            } else {
                filingService.recordChannelUnavailable(
                        filing.getId(), response.getChannelUnavailableReason());
            }
        } catch (Exception e) {
            log.error("Hometax filing-register write failed for period={}: {}",
                    period, e.getMessage(), e);
        }
    }
}
