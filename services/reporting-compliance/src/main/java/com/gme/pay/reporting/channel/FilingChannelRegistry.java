package com.gme.pay.reporting.channel;

import com.gme.pay.reporting.persistence.ReportFiling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for <b>which regulatory lanes can actually transmit a report</b>.
 *
 * <h2>Why this exists (GAP T5-2)</h2>
 * The three lanes in this service do real work — KoFIU CTR/STR threshold computation,
 * BOK FX1014/FX1015 mapping and fixed-width file generation, Hometax monthly VAT
 * aggregation. None of them has a transmission channel: BOK SFTP and the Hometax NTS
 * mTLS endpoint are externally gated (OI-02 / OI-03) and the KoFIU electronic feed
 * endpoint is unconfirmed. Before this class existed, the stub channel clients
 * fabricated acknowledgements ({@code "ACCEPTED"}, {@code "STUB-<uuid>"}) and a filing
 * could be recorded as SUBMITTED/CONFIRMED although nothing had left the JVM.
 *
 * <p>The registry is deliberately <b>configuration-driven only</b>: a channel is live
 * when — and only when — its endpoint/credential configuration is present and is not a
 * placeholder. It has no way to be talked into {@code true} by a stub client.
 * {@link com.gme.pay.reporting.persistence.ReportFilingService} consults it before it
 * will persist {@code TRANSMITTED} or {@code ACKNOWLEDGED}, which is what makes a
 * fabricated acceptance structurally impossible rather than merely discouraged.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code gmepay.reporting.bok.channel.endpoint} — BOK SFTP endpoint (blank = no channel)</li>
 *   <li>{@code gmepay.reporting.kofiu.channel.endpoint} — KoFIU feed endpoint (blank = no channel)</li>
 *   <li>{@code gmepay.hometax.base-url} + {@code gmepay.hometax.cert-id} — NTS API base URL
 *       and the lib-vault id of the mTLS client certificate. A blank cert id, or the
 *       well-known placeholder {@code stub-cert-id}, means no channel.</li>
 * </ul>
 * All three default to "no channel", so a lane can only ever become live by an explicit,
 * deliberate deployment-time configuration change.
 */
@Component
public class FilingChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(FilingChannelRegistry.class);

    /**
     * Placeholder cert ids that must never count as a configured NTS credential.
     * {@code stub-cert-id} is the literal shipped in {@code application.yml}.
     */
    private static final List<String> PLACEHOLDER_CERT_IDS = List.of("stub-cert-id", "stub", "changeme", "todo");

    private final Map<ReportFiling.Lane, FilingChannelStatus> statuses;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this
     * {@code @Component} has more than one constructor (Spring 6 requires it).
     */
    @Autowired
    public FilingChannelRegistry(
            @Value("${gmepay.reporting.bok.channel.endpoint:}") String bokEndpoint,
            @Value("${gmepay.reporting.kofiu.channel.endpoint:}") String kofiuEndpoint,
            @Value("${gmepay.hometax.base-url:}") String hometaxBaseUrl,
            @Value("${gmepay.hometax.cert-id:}") String hometaxCertId) {

        Map<ReportFiling.Lane, FilingChannelStatus> map = new EnumMap<>(ReportFiling.Lane.class);

        map.put(ReportFiling.Lane.BOK, blank(bokEndpoint)
                ? FilingChannelStatus.unavailable(ReportFiling.Lane.BOK,
                        "No BOK transmission channel: gmepay.reporting.bok.channel.endpoint is not set "
                                + "(BOK SFTP endpoint + credentials externally gated, OI-03). "
                                + "Files are generated locally and never transmitted.")
                : FilingChannelStatus.live(ReportFiling.Lane.BOK));

        map.put(ReportFiling.Lane.KOFIU, blank(kofiuEndpoint)
                ? FilingChannelStatus.unavailable(ReportFiling.Lane.KOFIU,
                        "No KoFIU transmission channel: gmepay.reporting.kofiu.channel.endpoint is not set "
                                + "(KoFIU electronic reporting endpoint + credentials unconfirmed, OI-03). "
                                + "CTR/STR feeds are generated locally and never transmitted.")
                : FilingChannelStatus.live(ReportFiling.Lane.KOFIU));

        map.put(ReportFiling.Lane.HOMETAX, hometaxLive(hometaxBaseUrl, hometaxCertId)
                ? FilingChannelStatus.live(ReportFiling.Lane.HOMETAX)
                : FilingChannelStatus.unavailable(ReportFiling.Lane.HOMETAX,
                        hometaxUnavailableReason(hometaxBaseUrl, hometaxCertId)));

        this.statuses = Map.copyOf(map);
    }

    /**
     * Test/fallback constructor: every lane explicitly has no transmission channel.
     * This is the honest default state of the platform, not a convenience shortcut —
     * there is intentionally <b>no</b> constructor that can mark a lane live without
     * real channel configuration.
     */
    public static FilingChannelRegistry noChannelsConfigured() {
        return new FilingChannelRegistry("", "", "", "");
    }

    /** All lane channel statuses, in lane order. Never empty. */
    public List<FilingChannelStatus> statuses() {
        return List.of(
                statusOf(ReportFiling.Lane.BOK),
                statusOf(ReportFiling.Lane.KOFIU),
                statusOf(ReportFiling.Lane.HOMETAX));
    }

    /** The channel status for one lane. */
    public FilingChannelStatus statusOf(ReportFiling.Lane lane) {
        return statuses.get(lane);
    }

    /** {@code true} only when the lane has a real, configured transmission channel. */
    public boolean isLive(ReportFiling.Lane lane) {
        return statusOf(lane).live();
    }

    /** Why the lane cannot transmit; {@code null} when it can. */
    public String unavailableReason(ReportFiling.Lane lane) {
        return statusOf(lane).reason();
    }

    /**
     * Logs the channel board once the context is up, so the "nothing is filed" fact is
     * visible in the startup log of every environment and not only in the API.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logChannelBoard() {
        for (FilingChannelStatus s : statuses()) {
            if (s.live()) {
                log.info("Regulatory filing channel LIVE: lane={} — filings may reach {}",
                        s.lane(), ReportFiling.Status.ACKNOWLEDGED);
            } else {
                log.warn("Regulatory filing channel NOT CONFIGURED: lane={} — filings terminate at {}. {}",
                        s.lane(), s.reachableStatus(), s.reason());
            }
        }
    }

    private static boolean hometaxLive(String baseUrl, String certId) {
        if (blank(baseUrl) || blank(certId)) {
            return false;
        }
        String normalised = certId.trim().toLowerCase();
        return PLACEHOLDER_CERT_IDS.stream().noneMatch(normalised::startsWith);
    }

    private static String hometaxUnavailableReason(String baseUrl, String certId) {
        StringBuilder sb = new StringBuilder("No Hometax (NTS) transmission channel: ");
        if (blank(baseUrl)) {
            sb.append("gmepay.hometax.base-url is not set; ");
        }
        if (blank(certId)) {
            sb.append("gmepay.hometax.cert-id is not set; ");
        } else if (!hometaxLive("x", certId)) {
            sb.append("gmepay.hometax.cert-id is the placeholder '").append(certId)
                    .append("' — no real mTLS certificate is installed; ");
        }
        sb.append("(NTS e-tax-invoice mTLS onboarding externally gated, OI-02). ")
                .append("Invoices are aggregated locally and never transmitted.");
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
