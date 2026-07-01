package com.gme.pay.notify.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/**
 * Spring Data repository for {@link AlertEventEntity} (WBS 8.6-T24).
 */
public interface AlertEventRepository extends JpaRepository<AlertEventEntity, Long> {

    /**
     * Dedup probe backing the queue-depth alert-storm suppressor: {@code true} if an
     * <em>unacknowledged</em> alert of {@code alertType} for {@code partnerId} was
     * fired after {@code cutoff}. {@code partnerId} must be non-null (a global
     * queue-depth breach uses a sentinel partner id, e.g. {@code 0}).
     */
    boolean existsByPartnerIdAndAlertTypeAndAcknowledgedAtIsNullAndFiredAtAfter(
            Long partnerId, String alertType, Instant cutoff);
}
