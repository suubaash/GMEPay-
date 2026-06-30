package com.gme.pay.qr.prefunding;

import com.gme.pay.qr.domain.cpm.PrefundingReservationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained {@link PrefundingReservationPort} fallback for tests and no-prefunding runs
 * (Phase 2, IR-qr-3). Wired via {@link ConditionalOnMissingBean} when
 * {@link RestPrefundingReservationClient} is not enabled
 * ({@code gmepay.prefunding.reserve.enabled} unset/false).
 *
 * <p>Tracks reservations in-memory keyed by idempotency key so the generate → expiry flow is
 * exercisable without prefunding running. Idempotent: a repeated reserve for the same key returns
 * the original handle; a release for an unknown key is a no-op. Never throws INSUFFICIENT_PREFUNDING
 * (no balance model here) — the 402 path is covered against the gated REST client.
 */
@Component
@ConditionalOnMissingBean(RestPrefundingReservationClient.class)
public class InMemoryPrefundingReservationFixture implements PrefundingReservationPort {

    private final Map<String, Reservation> byKey = new ConcurrentHashMap<>();

    @Override
    public Reservation reserve(long partnerId, BigDecimal amountUsd, String idempotencyKey, String txnRef) {
        return byKey.computeIfAbsent(idempotencyKey, k ->
                new Reservation("LOCAL-RSV-" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 16).toUpperCase(), amountUsd));
    }

    @Override
    public void release(long partnerId, String reservationId, String idempotencyKey, String reason) {
        byKey.remove(idempotencyKey);
    }
}
