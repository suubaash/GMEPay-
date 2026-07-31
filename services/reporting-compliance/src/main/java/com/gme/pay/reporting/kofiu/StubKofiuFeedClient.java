package com.gme.pay.reporting.kofiu;

import com.gme.pay.reporting.channel.FilingChannelRegistry;
import com.gme.pay.reporting.channel.FilingTransmissionResult;
import com.gme.pay.reporting.persistence.ReportFiling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * No-channel implementation of {@link KofiuFeedClient} — active whenever no other bean of
 * type {@link KofiuFeedClient} is present, i.e. in every environment today.
 *
 * <h2>What changed and why (GAP T5-2)</h2>
 * This client used to return a fabricated receipt id {@code "STUB-" + UUID}, which made a
 * never-transmitted CTR/STR feed look acknowledged in the logs and in the filing register.
 * It now returns {@link FilingTransmissionResult#notTransmitted(String)} carrying the
 * reason no KoFIU channel exists, and structurally cannot attach a receipt id.
 *
 * <p>The lane's real capability — CTR/STR threshold computation and daily feed-file
 * generation ({@link KofiuReportService}, {@link KofiuFeedFileBuilder}) — is unaffected.
 *
 * <p>Replace with a real SFTP or HTTP client once KoFIU submission credentials and
 * endpoint details are confirmed (OI-03 pending).
 */
@Component
@ConditionalOnMissingBean(value = KofiuFeedClient.class,
        ignored = StubKofiuFeedClient.class)
public class StubKofiuFeedClient implements KofiuFeedClient {

    private static final Logger log = LoggerFactory.getLogger(StubKofiuFeedClient.class);

    private final FilingChannelRegistry channelRegistry;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Component} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public StubKofiuFeedClient(FilingChannelRegistry channelRegistry) {
        this.channelRegistry = Objects.requireNonNull(channelRegistry, "channelRegistry");
    }

    /** Convenience constructor for tests: no channel configured. */
    public StubKofiuFeedClient() {
        this(FilingChannelRegistry.noChannelsConfigured());
    }

    @Override
    public FilingTransmissionResult submit(Path feedFile, KofiuReportBatch batch) {
        String reason = channelRegistry.unavailableReason(ReportFiling.Lane.KOFIU);
        if (reason == null) {
            throw new IllegalStateException(
                    "gmepay.reporting.kofiu.channel.endpoint is configured but no production "
                            + "KofiuFeedClient bean is wired — refusing to pretend the feed was sent");
        }
        log.warn("KoFIU feed NOT TRANSMITTED (generated locally only): file={}, date={}, "
                        + "ctr={}, str={} — {}",
                feedFile,
                batch.getReportDate(),
                batch.getCtrReports().size(),
                batch.getStrReports().size(),
                reason);
        return FilingTransmissionResult.notTransmitted(reason);
    }
}
