package com.gme.pay.payment.persistence;

import com.gme.pay.kyb.PaymentParty;
import com.gme.pay.kyb.UnscreenedReason;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts payments accepted without a sanctions/PEP screening, durably and queryably — gap <b>T5-3</b>.
 *
 * <p>This is the component that turns "we have no transaction screening" from an unquantified statement
 * in an audit report into a number someone can be asked about. It follows the shape
 * {@link OpsAlertArchive} and {@code RevenuePostingFailureStore} established in this service: a thin
 * {@code @Service} over a Spring Data repository whose write path <b>never throws</b>.
 *
 * <h2>Never throws — and why that is the right trade here</h2>
 * <p>{@link #countUnscreened} runs inside the live authorize path. A database hiccup while recording a
 * coverage statistic must not turn a payment into a 500: the payment's own correctness does not depend
 * on this row. A failed write is logged at ERROR (the last-resort signal) and swallowed, and the ops
 * alert is raised regardless by the caller, so a DB outage degrades the COUNT, not the visibility.
 *
 * <p>The corollary is stated plainly because it matters to anyone relying on these figures: the count is
 * a <b>lower bound</b>. It is not transactional with the payment and it is not hash-chained (ADR-007) —
 * a write that fails is a payment that is missing from the total. That is acceptable for measuring the
 * size of a known gap; it would NOT be acceptable for a screening decision log, which is one more reason
 * the real provider's log has to be its own thing.
 *
 * <h2>{@code REQUIRES_NEW}</h2>
 * <p>The increment runs in its own transaction, for the same reason lib-audit's authentication-failure
 * rows do (gap T5-1): the caller's transaction may go on to roll back — a downstream limit breach, a
 * prefunding failure, a duplicate-authorize compensation — and the fact that a party went unscreened at
 * the gate is true regardless of whether that particular payment eventually completed. Attaching the
 * count to the caller's transaction would quietly under-report exactly the busy, failing periods where
 * the number matters most.
 */
@Service
public class UnscreenedPaymentCounter {

    private static final Logger log = LoggerFactory.getLogger(UnscreenedPaymentCounter.class);

    /** Hard ceiling on any single read, matching {@link OpsAlertArchive#MAX_LIMIT}. */
    public static final int MAX_LIMIT = 500;

    /** Applied when a caller passes a non-positive limit. */
    public static final int DEFAULT_LIMIT = 50;

    private final UnscreenedPaymentRepository repository;
    private final Clock clock;

    public UnscreenedPaymentCounter(UnscreenedPaymentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Record that one payment was accepted with {@code party} unscreened because {@code reason}.
     *
     * <p>Increment-then-insert-then-retry: the {@code UPDATE} is the common path (a row already exists
     * for today), the {@code INSERT} is the first occurrence of the day, and the retry handles the race
     * where two payments both find no row — the V010 unique index rejects the loser's insert and the
     * loser then increments the winner's row. Never throws.
     *
     * @param reason     why nothing was screened
     * @param party      which party went unscreened
     * @param providerId the provider in force ({@code "none"} when none is configured)
     * @param partnerRef partner code the traffic arrived under; {@code null} becomes {@code "unknown"}
     * @param paymentRef opaque payment reference for the evidence anchor; may be {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void countUnscreened(UnscreenedReason reason,
                                PaymentParty party,
                                String providerId,
                                String partnerRef,
                                String paymentRef) {
        if (reason == null || party == null) {
            return;
        }
        Instant now = Instant.now(clock);
        LocalDate gapDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        String provider = providerId == null || providerId.isBlank() ? "none" : providerId;
        String partner = partnerRef == null || partnerRef.isBlank()
                ? UnscreenedPaymentEntity.UNKNOWN_PARTNER : partnerRef.trim();
        try {
            if (repository.increment(gapDate, reason.name(), party.name(), provider, partner,
                    now, paymentRef) > 0) {
                return;
            }
            try {
                repository.saveAndFlush(new UnscreenedPaymentEntity(
                        gapDate, reason.name(), party.name(), provider, partner, paymentRef, now));
            } catch (DataIntegrityViolationException raceLost) {
                // Another payment inserted the same key first; increment theirs instead of losing ours.
                if (repository.increment(gapDate, reason.name(), party.name(), provider, partner,
                        now, paymentRef) == 0) {
                    log.error("UNSCREENED PAYMENT COUNT LOST for reason={} party={} provider={}:"
                                    + " insert lost the race and the winning row could not be found."
                                    + " The unscreened total is now an under-count.",
                            reason, party, provider);
                }
            }
        } catch (RuntimeException e) {
            // Coverage measurement must never break the pay path. The caller still raises the ops alert.
            log.error("FAILED to record an unscreened payment (reason={} party={} provider={}) — the"
                            + " T5-3 unscreened total is now an under-count for {}: {}",
                    reason, party, provider, gapDate, e.toString());
        }
    }

    /** Recent aggregate rows, newest date first, bounded. */
    @Transactional(readOnly = true)
    public List<UnscreenedPaymentEntity> recent(LocalDate from, String reasonCode, int limit) {
        int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return repository.findRecent(from, blankToNull(reasonCode), PageRequest.of(0, capped));
    }

    /**
     * Total unscreened-party count from {@code from} onwards ({@code null} = all time).
     *
     * <p>Zero means ZERO COUNTED, which is not the same as "screened" — read it together with the
     * configured provider (see {@code PaymentScreeningGate.coverage()}).
     */
    @Transactional(readOnly = true)
    public long total(LocalDate from) {
        Long sum = repository.totalFrom(from);
        return sum == null ? 0L : sum;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
