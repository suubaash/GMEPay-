package com.gme.pay.prefunding.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #3 — the @Scheduled outbox drain carries a @SchedulerLock so only one replica drains per tick.
 * Reflection-only (no Spring context / broker needed): asserts the annotation is present and named.
 */
class OutboxPublisherShedLockTest {

    @Test
    @DisplayName("publishPending() is annotated with @SchedulerLock (distributed lock, #3)")
    void publishPending_hasSchedulerLock() throws NoSuchMethodException {
        Method m = OutboxPublisher.class.getDeclaredMethod("publishPending");
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertNotNull(lock, "@SchedulerLock must guard the scheduled outbox drain");
        assertEquals(OutboxPublisher.LOCK_NAME, lock.name());
        assertTrue(lock.lockAtMostFor().startsWith("PT"), "lockAtMostFor safety net must be set");
    }
}
