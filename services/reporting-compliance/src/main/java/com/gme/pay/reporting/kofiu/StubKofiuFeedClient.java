package com.gme.pay.reporting.kofiu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Default (stub) implementation of {@link KofiuFeedClient}.
 *
 * <p>No real KoFIU SFTP/API channel is available in this environment — this
 * stub logs the submission and returns a randomly-generated fake receipt id
 * of the form {@code STUB-{uuid}}. Active whenever no other bean of type
 * {@link KofiuFeedClient} is present.
 *
 * <p>Replace with a real SFTP or HTTP client once KoFIU submission credentials
 * and endpoint details are confirmed (OI-03 pending).
 */
@Component
@ConditionalOnMissingBean(value = KofiuFeedClient.class,
        ignored = StubKofiuFeedClient.class)
public class StubKofiuFeedClient implements KofiuFeedClient {

    private static final Logger log = LoggerFactory.getLogger(StubKofiuFeedClient.class);

    /** Prefix on all fake receipt ids — distinguishes them from production ids. */
    public static final String STUB_RECEIPT_PREFIX = "STUB-";

    @Override
    public String submit(Path feedFile, KofiuReportBatch batch) {
        String receiptId = STUB_RECEIPT_PREFIX + UUID.randomUUID();
        log.info("[STUB] KoFIU feed submitted (no real channel): file={}, date={}, "
                        + "ctr={}, str={}, fakeReceiptId={}",
                feedFile,
                batch.getReportDate(),
                batch.getCtrReports().size(),
                batch.getStrReports().size(),
                receiptId);
        return receiptId;
    }
}
