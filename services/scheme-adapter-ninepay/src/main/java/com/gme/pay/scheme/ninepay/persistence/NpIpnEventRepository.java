package com.gme.pay.scheme.ninepay.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository over {@code np_ipn_events}. */
public interface NpIpnEventRepository extends JpaRepository<NpIpnEventEntity, Long> {

    List<NpIpnEventEntity> findByRequestIdOrderByReceivedAtAsc(String requestId);

    /**
     * Replay-guard lookup (T5-4): the already-applied event for a 9Pay event identity, if
     * any. Backed by the UNIQUE constraint on {@code event_key} (V002), which is what makes
     * the guard hold under concurrent redeliveries as well.
     */
    Optional<NpIpnEventEntity> findByEventKey(String eventKey);

    /**
     * Every event already APPLIED to a payout, newest first — the ordering input for the
     * staleness / monotonic-status rules.
     */
    List<NpIpnEventEntity> findByRequestIdAndAppliedTrueOrderByReceivedAtDesc(String requestId);
}
