package com.gme.pay.reporting.hometax;

import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.persistence.ReportFiling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * No-channel implementation of {@link HometaxClient} — active by default when no
 * production {@link HometaxClient} bean is present, i.e. in every environment today.
 *
 * <h2>What changed and why (GAP T5-2)</h2>
 * This class used to fabricate an NTS acknowledgement: {@code invoiceId = "STUB-INV-"+seq},
 * a spec-shaped 24-char {@code ntsConfirmation}, and literal status {@code "ACCEPTED"}.
 * Anyone reading {@code report_filing}, the API or the logs saw an <i>accepted NTS
 * filing</i> for an invoice that never left the JVM — materially misleading to compliance
 * staff, an auditor or a regulator.
 *
 * <p>It now returns {@link HometaxInvoiceResponse#notFiled(String)}: status
 * {@link ReportFiling.Status#NOT_FILED_CHANNEL_UNAVAILABLE}, <b>no</b> invoice id, <b>no</b>
 * confirmation number, and the reason the NTS channel is missing. The real capability of
 * this lane (monthly VAT aggregation net of GME's spread and levy — see
 * {@link HometaxInvoiceService}) is untouched; only the fake acknowledgement is gone.
 *
 * <p>There is intentionally no code path here that can produce an accepted status: the
 * NTS mTLS submission must be implemented by a separate production {@link HometaxClient}
 * bean once OI-02 clears.
 */
@Component
@ConditionalOnMissingBean(value = HometaxClient.class, ignored = StubHometaxClient.class)
public class StubHometaxClient implements HometaxClient {

    private static final Logger log = LoggerFactory.getLogger(StubHometaxClient.class);

    private final FilingChannelRegistry channelRegistry;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Component} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public StubHometaxClient(FilingChannelRegistry channelRegistry) {
        this.channelRegistry = Objects.requireNonNull(channelRegistry, "channelRegistry");
    }

    /**
     * Convenience constructor for tests and direct instantiation: no channel configured,
     * which is this client's whole point.
     */
    public StubHometaxClient() {
        this(FilingChannelRegistry.noChannelsConfigured());
    }

    @Override
    public HometaxInvoiceResponse submitInvoice(HometaxInvoiceRequest request) {
        String reason = channelRegistry.unavailableReason(ReportFiling.Lane.HOMETAX);
        if (reason == null) {
            // A live Hometax channel is configured but this no-channel client is still the
            // wired implementation — fail loudly rather than silently not filing.
            throw new IllegalStateException(
                    "gmepay.hometax channel config is present but no production HometaxClient "
                            + "bean is wired — refusing to pretend an invoice was filed");
        }
        log.warn("Hometax e-tax-invoice NOT FILED (aggregation only): {}", reason);
        return HometaxInvoiceResponse.notFiled(reason);
    }
}
