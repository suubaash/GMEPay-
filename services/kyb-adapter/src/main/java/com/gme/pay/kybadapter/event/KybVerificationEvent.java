package com.gme.pay.kybadapter.event;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.kybadapter.kyb.KybDecision;
import com.gme.pay.kybadapter.kyb.KybVerificationResult;
import java.time.Instant;

/**
 * Domain event for one completed FULL KYB verification run, published to Kafka
 * topic {@code gmepay.kyb.verification} (ADR-001 topic naming: {@code gmepay.} +
 * {@link #eventType()}).
 *
 * <p>Distinct from {@link KybScreeningEvent} ({@code gmepay.kyb.screening},
 * sanctions-only): this carries the COLLAPSED orchestration decision (screening
 * + business-registration + documents) so config-registry's onboarding wizard
 * and reporting-compliance can react to the PASS/FAIL/MANUAL_REVIEW verdict
 * without re-deriving it. Keyed by {@code partnerCode} so all KYB events for one
 * partner land on the same partition in run order.
 *
 * <p>Only emitted for FRESH runs — an idempotent replay of a stored run does not
 * re-publish (the original run already fanned out).
 */
public record KybVerificationEvent(
        String partnerCode,
        String providerRef,
        KybDecision decision,
        String decisionReason,
        Instant screenedAt) implements DomainEvent {

    /** Builds the event straight from the orchestration {@link KybVerificationResult}. */
    public static KybVerificationEvent of(KybVerificationResult result) {
        return new KybVerificationEvent(
                result.partnerCode(),
                result.providerRef(),
                result.decision(),
                result.decisionReason(),
                result.screenedAt());
    }

    @Override
    public String eventType() {
        return "kyb.verification";
    }

    @Override
    public String aggregateId() {
        return partnerCode;
    }

    @Override
    public Instant occurredAt() {
        return screenedAt;
    }
}
