package com.gme.pay.settlement.transmission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for <b>whether a generated settlement file can actually be transmitted</b>.
 *
 * <h2>Why this exists (GAP T4-5 / CPO audit P13)</h2>
 * The outbound spine does real work: it books per-partner nets under Addendum-001, writes
 * {@code settlement_batches} + {@code settlement_lines}, builds the fixed-width ZP0061/ZP0063
 * request and ZP0065/ZP0066 detail files, checksums them and reconciles the scheme's confirmation
 * against the persisted lines. What it has never done is <b>send</b> one. The only
 * {@code SftpTransport} bean in the platform is {@code LocalDirSftpTransport}, which copies the
 * file into a local temp directory; real ZeroPay/KFTC SFTP needs scheme credentials and a
 * certification run, which is an external gate.
 *
 * <p>Before this class existed the reconciliation path <em>fast-forwarded</em> a batch through
 * {@code TRANSMITTED} on its way to {@code RECEIVED} purely as bookkeeping, so a batch that had
 * never been sent read as sent — and the BFF then hardcoded {@code status=COMPLETED} on top of
 * that. This registry makes the claim structurally impossible instead of merely discouraged:
 * {@link SettlementTransmissionRecorder} consults it before it will move a batch to
 * {@link SettlementTransmissionState#TRANSMITTED}, and nothing else may write that state.
 *
 * <p>It is deliberately <b>configuration-driven only</b>. A channel is live when — and only when —
 * an endpoint is configured and is not a placeholder or a local-directory stand-in. There is no
 * constructor, property or test hook that can talk it into {@code true} without one, and it
 * defaults to "no channel" so a lane can only become live by an explicit deployment-time change.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code gmepay.settlement.transmission.channel.endpoint} — the scheme SFTP endpoint
 *       (blank = no channel; this is the default)</li>
 *   <li>{@code gmepay.settlement.transmission.channel.credential-id} — the lib-vault id of the
 *       SFTP key/credential. Blank, or a well-known placeholder, means no channel: an endpoint
 *       with no credential cannot transmit.</li>
 * </ul>
 */
@Component
public class SettlementTransmissionChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(SettlementTransmissionChannelRegistry.class);

    /**
     * Endpoint shapes that must never count as a real transmission channel. {@code LocalDirSftpTransport}
     * is the platform's actual transport bean and writes to a temp directory — pointing the endpoint at a
     * local path or a file URL is exactly the "looks configured, goes nowhere" case this gap is about.
     */
    private static final List<String> LOCAL_ENDPOINT_PREFIXES =
            List.of("file:", "local", "/tmp", "./", "temp", "classpath:");

    /** Placeholder credential ids that must never count as an installed SFTP credential. */
    private static final List<String> PLACEHOLDER_CREDENTIALS =
            List.of("stub", "changeme", "change-me", "todo", "placeholder", "replace", "dummy", "test-key");

    private final SettlementTransmissionChannelStatus status;

    /**
     * Spring constructor. {@code @Autowired} declared explicitly because this {@code @Component} has
     * more than one constructor — the repo's documented two-constructor gotcha; without it Spring has
     * nothing to choose with and the context fails to start.
     */
    @Autowired
    public SettlementTransmissionChannelRegistry(
            @Value("${gmepay.settlement.transmission.channel.endpoint:}") String endpoint,
            @Value("${gmepay.settlement.transmission.channel.credential-id:}") String credentialId) {
        this.status = classify(endpoint, credentialId);
    }

    /**
     * Test/fallback factory: explicitly no transmission channel. This is the honest default state of
     * the platform rather than a convenience shortcut — there is intentionally <b>no</b> factory that
     * can mark the channel live without real configuration.
     */
    public static SettlementTransmissionChannelRegistry noChannelConfigured() {
        return new SettlementTransmissionChannelRegistry("", "");
    }

    /** The channel status. Never null. */
    public SettlementTransmissionChannelStatus status() {
        return status;
    }

    /** {@code true} only when a real, configured transmission channel exists. */
    public boolean isLive() {
        return status.live();
    }

    /**
     * The most advanced transmission state a batch can reach in this deployment —
     * {@link SettlementTransmissionState#NOT_TRANSMITTED_CHANNEL_UNAVAILABLE} while no channel exists.
     * This is what the generation path stamps onto a freshly generated batch, so the row itself carries
     * the fact rather than a reader having to infer it.
     */
    public SettlementTransmissionState reachableState() {
        return status.reachableState();
    }

    /** Why the channel cannot transmit; {@code null} when it can. */
    public String unavailableReason() {
        return status.reason();
    }

    /**
     * Logs the channel board once the context is up, so "no settlement file has ever been transmitted"
     * is visible in the startup log of every environment and not only in the API.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logChannelBoard() {
        if (status.live()) {
            log.info("Settlement transmission channel LIVE: channelId={} — batches may reach {}",
                    status.channelId(), SettlementTransmissionState.TRANSMITTED);
        } else {
            log.warn("Settlement transmission channel NOT CONFIGURED — every generated batch terminates "
                    + "at {}. {}", status.reachableState(), status.reason());
        }
    }

    private static SettlementTransmissionChannelStatus classify(String endpoint, String credentialId) {
        if (blank(endpoint)) {
            return SettlementTransmissionChannelStatus.unavailable(
                    "No settlement transmission channel: "
                            + "gmepay.settlement.transmission.channel.endpoint is not set "
                            + "(scheme SFTP endpoint + credentials + a certification run are externally "
                            + "gated). Settlement files are generated and checksummed locally and are "
                            + "never transmitted.");
        }
        String normalisedEndpoint = endpoint.trim().toLowerCase(Locale.ROOT);
        if (LOCAL_ENDPOINT_PREFIXES.stream().anyMatch(normalisedEndpoint::startsWith)) {
            return SettlementTransmissionChannelStatus.unavailable(
                    "No settlement transmission channel: the configured endpoint '" + endpoint.trim()
                            + "' is a local path, not a scheme endpoint — this is the "
                            + "LocalDirSftpTransport temp-directory case, which writes the file to disk "
                            + "and sends nothing.");
        }
        if (blank(credentialId)) {
            return SettlementTransmissionChannelStatus.unavailable(
                    "No settlement transmission channel: an endpoint is configured ('" + endpoint.trim()
                            + "') but gmepay.settlement.transmission.channel.credential-id is not set, "
                            + "so there is no SFTP credential to authenticate with.");
        }
        String normalisedCredential = credentialId.trim().toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_CREDENTIALS.stream().anyMatch(normalisedCredential::startsWith)) {
            return SettlementTransmissionChannelStatus.unavailable(
                    "No settlement transmission channel: "
                            + "gmepay.settlement.transmission.channel.credential-id is the placeholder '"
                            + credentialId.trim() + "' — no real SFTP credential is installed.");
        }
        return SettlementTransmissionChannelStatus.live(endpoint.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
