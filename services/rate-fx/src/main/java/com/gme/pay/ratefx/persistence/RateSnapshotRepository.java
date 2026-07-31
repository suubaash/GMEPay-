package com.gme.pay.ratefx.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** Spring Data repository for treasury rate snapshots. */
public interface RateSnapshotRepository extends JpaRepository<RateSnapshotEntity, String> {

    /**
     * LIVE-source resolution (RATE-04 §3.2): the most recent snapshot for a currency
     * with the given source whose effective_at is at or before {@code asOf}.
     */
    Optional<RateSnapshotEntity> findFirstByCurrencyCodeAndSourceAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
            String currencyCode, String source, Instant asOf);

    /**
     * The rate <b>in force</b> for a currency at {@code asOf}, whatever fed it — the most recent
     * effective snapshot across all sources, with {@code captured_at} as the tie-break so two rows
     * sharing an effective instant resolve deterministically to the one written later.
     *
     * <p>Added for the T5-1 audit trail: an audit row that recorded only the new rate would not let a
     * reader see what a manual override actually changed, and "USD/MNT was set to 3450" is a very
     * different finding from "USD/MNT was moved from 3450 to 3900". Source-agnostic on purpose,
     * because the whole point of a MANUAL row is that it displaces a LIVE one.
     *
     * <p>Read-only and off the quote hot path — the resolvers keep using the source-scoped query
     * above, whose semantics are unchanged.
     */
    Optional<RateSnapshotEntity> findFirstByCurrencyCodeAndEffectiveAtLessThanEqualOrderByEffectiveAtDescCapturedAtDesc(
            String currencyCode, Instant asOf);
}
