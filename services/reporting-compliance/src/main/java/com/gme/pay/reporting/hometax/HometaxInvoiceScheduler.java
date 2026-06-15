package com.gme.pay.reporting.hometax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Spring constructor. {@code @Autowired} declared explicitly for defensive style
     * (safe if a second constructor is added later). Tests may call this constructor
     * directly — {@code @Value} annotations on parameters are just Spring metadata.
     */
    @Autowired
    public HometaxInvoiceScheduler(
            HometaxInvoiceService invoiceService,
            @Value("${gmepay.reporting.hometax.enabled:false}") boolean enabled,
            @Value("${gmepay.reporting.hometax.partner-code:GMEREMIT}") String partnerCode,
            @Value("${gmepay.reporting.hometax.fee-rate:0.0150}") BigDecimal feeRate,
            @Value("${gmepay.hometax.cert-id:stub-cert-id}") String certId) {
        this.invoiceService = invoiceService;
        this.enabled = enabled;
        this.partnerCode = partnerCode;
        this.feeRate = feeRate;
        this.certId = certId;
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

            log.info("Hometax invoice submitted: period={} invoiceId={} ntsConfirmation={} status={}",
                    period,
                    response.getInvoiceId(),
                    response.getNtsConfirmation(),
                    response.getStatus());
        } catch (Exception ex) {
            log.error("Hometax monthly invoice job failed for period={}: {}", period, ex.getMessage(), ex);
            // Do not rethrow — scheduler must survive individual-run failures
        }
    }
}
