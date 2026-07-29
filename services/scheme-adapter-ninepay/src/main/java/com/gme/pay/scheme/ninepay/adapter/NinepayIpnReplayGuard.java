package com.gme.pay.scheme.ninepay.adapter;

import com.gme.pay.scheme.ninepay.dto.IpnRequest;
import com.gme.pay.scheme.ninepay.persistence.IpnRejectReason;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventEntity;
import com.gme.pay.scheme.ninepay.persistence.NpIpnEventRepository;
import com.gme.pay.scheme.ninepay.status.PayoutStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether an inbound 9Pay IPN may be APPLIED to a payout — gap <b>T5-4</b>
 * ("no IPN replay protection").
 *
 * <h2>The hole this closes</h2>
 *
 * <p>{@code handleIpn} used to audit the push and then call {@code recordIpn}
 * unconditionally, so anyone holding a captured, validly-signed IPN could resend it and
 * re-apply its state. Two of 9Pay's message codes make that expensive: <b>000</b> is
 * success and <b>009</b> is a bank reversal <em>after</em> success. A replayed 009
 * re-reverses; a replayed 000 sent after a 009 makes a reversed payout look paid again.
 *
 * <h2>The rules</h2>
 *
 * <ol>
 *   <li><b>Identity dedupe.</b> 9Pay ships no IPN id, so the event's identity is the
 *       {@code (request_id, trans_id, code)} triple. Its hash is stored in the UNIQUE
 *       {@code np_ipn_events.event_key} (V002), so at most one delivery per identity is
 *       ever applied — process-local or racing another instance. A resend is
 *       {@link IpnRejectReason#DUPLICATE}: audited, no mutation, still 2xx-ACKed (the
 *       right answer to "I already have this" is not an error that provokes more retries).</li>
 *   <li><b>Staleness by SIGNED timestamp.</b> An IPN whose {@code created_at} predates the
 *       newest already-applied event for that payout is {@link IpnRejectReason#STALE_ORDER}.
 *       {@code created_at} is inside the signed string, so it cannot be back-dated by a
 *       replayer. <b>Equal timestamps pass</b> — see the 009 note below.</li>
 *   <li><b>Monotonic status.</b> An IPN that would move the payout backwards through its
 *       lifecycle is {@link IpnRejectReason#STATUS_REGRESSION}: nothing overwrites
 *       {@code REVERSED}, and a late {@code PENDING} cannot undo a {@code SUCCESS}.</li>
 *   <li><b>Age window.</b> {@code created_at} older than
 *       {@code gmepay.scheme.ninepay.ipn.max-age-minutes} is {@link IpnRejectReason#EXPIRED}
 *       (0 disables). Default is deliberately generous — 7 days — because genuine bank
 *       reversals arrive days late and dropping one would silently keep money paid out that
 *       the bank took back.</li>
 * </ol>
 *
 * <h2>Why the genuine 000 → 009 reversal still works</h2>
 *
 * <p>This was the trap to avoid. 9Pay's IPN signature covers
 * {@code request_id|partner_id|trans_id|request_amount|fee|transfer_amount|type|status|created_at}
 * and <b>not</b> {@code code}; a reversal keeps wire status {@code SUCCESS} and reuses the
 * transfer's {@code created_at}. So a genuine 009 is byte-identical to the 000 before it
 * <em>except</em> for the unsigned {@code code}. Consequences, both deliberate:
 * <ul>
 *   <li>dedupe keys on the triple (which includes {@code code}) rather than on the signed
 *       body, so the 009 is a distinct event and is applied — had the key been the signed
 *       content, the real reversal would have been swallowed as a duplicate;</li>
 *   <li>the staleness rule accepts EQUAL timestamps, because the 009 carries the same
 *       {@code created_at} as the 000;</li>
 *   <li>{@code SUCCESS -> REVERSED} is explicitly not a status regression.</li>
 * </ul>
 *
 * <p><b>Residual, external:</b> because {@code code} is outside 9Pay's signed string, a
 * captured 000 with {@code code} rewritten to 009 still verifies cryptographically. No
 * local rule can distinguish it from a genuine reversal — the compensating controls are the
 * IPN-edge IP allowlist (still missing, network layer) and the fact that the dedupe key
 * bounds the damage to a single reversal per identity. Fixing it properly needs 9Pay to
 * include {@code code} in the signed string (external ask; see the T5-4 report).
 */
@Component
public class NinepayIpnReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(NinepayIpnReplayGuard.class);

    /** 9Pay timestamps are GMT+7, {@code Y-m-d H:i:s}. */
    private static final ZoneId NINEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Default acceptance window: 7 days (bank reversals are legitimately very late). */
    public static final long DEFAULT_MAX_AGE_MINUTES = 10_080L;

    private final NpIpnEventRepository ipnEvents;
    private final Clock clock;
    private final long maxAgeMinutes;

    /** Spring constructor. {@code @Autowired} required — this class has 2 constructors. */
    @Autowired
    public NinepayIpnReplayGuard(
            NpIpnEventRepository ipnEvents,
            @Value("${gmepay.scheme.ninepay.ipn.max-age-minutes:10080}") long maxAgeMinutes) {
        this(ipnEvents, Clock.systemUTC(), maxAgeMinutes);
    }

    /** Test constructor — fixed clock + explicit window. */
    public NinepayIpnReplayGuard(NpIpnEventRepository ipnEvents, Clock clock, long maxAgeMinutes) {
        this.ipnEvents = Objects.requireNonNull(ipnEvents);
        this.clock = Objects.requireNonNull(clock);
        this.maxAgeMinutes = maxAgeMinutes;
    }

    /**
     * The event's identity as a 64-char lowercase-hex SHA-256 of
     * {@code request_id|trans_id|code}. Hashed rather than stored raw so one bounded UNIQUE
     * column covers a triple whose parts are individually up to 50/20/3 chars, on both
     * PostgreSQL and H2.
     */
    public String eventKey(String requestId, String transId, String code) {
        String identity = norm(requestId) + "|" + norm(transId) + "|" + norm(code);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Verdict for one signature-valid IPN.
     *
     * @param ipn            the parsed push
     * @param eventKey       its identity, from {@link #eventKey}
     * @param incomingStatus the {@link PayoutStatus} this IPN maps to
     * @param currentStatus  the payout's status right now
     * @return the reason to withhold application, or {@link Optional#empty()} to apply
     */
    public Optional<IpnRejectReason> evaluate(IpnRequest ipn, String eventKey,
                                              PayoutStatus incomingStatus,
                                              PayoutStatus currentStatus) {
        // 1. Exact replay / redelivery of an event we already applied.
        if (ipnEvents.findByEventKey(eventKey).isPresent()) {
            log.warn("9Pay IPN replay ignored: request_id={} trans_id={} code={} was already applied",
                    ipn.requestId(), ipn.transId(), ipn.code());
            return Optional.of(IpnRejectReason.DUPLICATE);
        }

        Optional<Instant> createdAt = parseCreatedAt(ipn.createdAt());

        // 2. Beyond the acceptance window.
        if (maxAgeMinutes > 0 && createdAt.isPresent()) {
            Instant cutoff = Instant.now(clock).minus(Duration.ofMinutes(maxAgeMinutes));
            if (createdAt.get().isBefore(cutoff)) {
                log.warn("9Pay IPN outside the {}-minute acceptance window ignored: request_id={} "
                                + "code={} created_at={}",
                        maxAgeMinutes, ipn.requestId(), ipn.code(), ipn.createdAt());
                return Optional.of(IpnRejectReason.EXPIRED);
            }
        }

        // 3. Out of order against the newest event we already applied. Equality passes: a
        //    genuine 009 reversal reuses the 000's created_at.
        if (createdAt.isPresent()) {
            Optional<Instant> newestApplied = newestAppliedCreatedAt(ipn.requestId());
            if (newestApplied.isPresent() && createdAt.get().isBefore(newestApplied.get())) {
                log.warn("9Pay IPN out of order ignored: request_id={} code={} created_at={} predates "
                                + "the last applied event ({})",
                        ipn.requestId(), ipn.code(), ipn.createdAt(), newestApplied.get());
                return Optional.of(IpnRejectReason.STALE_ORDER);
            }
        }

        // 4. Lifecycle regression (the "stale 000 must not un-reverse a 009" rule).
        if (isRegression(incomingStatus, currentStatus)) {
            log.warn("9Pay IPN status regression ignored: request_id={} code={} would move payout "
                            + "{} -> {}",
                    ipn.requestId(), ipn.code(), currentStatus, incomingStatus);
            return Optional.of(IpnRejectReason.STATUS_REGRESSION);
        }

        return Optional.empty();
    }

    /**
     * Lifecycle rank. {@code SUCCESS} and {@code FAILED} share a rank so a verdict can be
     * corrected, while {@code REVERSED} outranks both — that is what lets the genuine
     * post-success reversal through and stops anything from walking back out of it.
     * {@code SUBMITTED}/{@code UNKNOWN} rank lowest, so an unrecognised status can never
     * demote a decided payout.
     */
    private static int rank(PayoutStatus status) {
        return switch (status) {
            case SUBMITTED, UNKNOWN -> 0;
            case PENDING -> 1;
            case PROCESSING -> 2;
            case HELD -> 3;
            case SUCCESS, FAILED -> 4;
            case REVERSED -> 5;
        };
    }

    private static boolean isRegression(PayoutStatus incoming, PayoutStatus current) {
        return rank(incoming) < rank(current);
    }

    private Optional<Instant> newestAppliedCreatedAt(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<NpIpnEventEntity> applied =
                ipnEvents.findByRequestIdAndAppliedTrueOrderByReceivedAtDesc(requestId);
        // Newest by SIGNED timestamp, not by arrival: an attacker controls arrival order.
        return applied.stream()
                .map(NpIpnEventEntity::getSchemeCreatedAt)
                .map(this::parseCreatedAt)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Instant::compareTo);
    }

    /**
     * Parses 9Pay's {@code Y-m-d H:i:s} GMT+7 stamp. An absent/unparseable value yields
     * empty, which SKIPS the timestamp rules rather than rejecting — dropping a genuine
     * reversal because 9Pay changed a date format would be the worse failure, and the
     * dedupe + monotonic rules still stand on their own.
     */
    private Optional<Instant> parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDateTime.parse(createdAt.trim(), CREATED_AT)
                    .atZone(NINEPAY_ZONE).toInstant());
        } catch (RuntimeException e) {
            log.warn("9Pay IPN created_at '{}' is not the documented 'Y-m-d H:i:s' format; "
                    + "timestamp-ordering rules skipped for this event", createdAt);
            return Optional.empty();
        }
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim();
    }
}
